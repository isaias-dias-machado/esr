package dev.pl36.net;

import dev.pl36.net.api.ControlMessenger;
import dev.pl36.net.api.ControlMessengerListener;
import dev.pl36.routing.RoutingTable;
import dev.pl36.scenario.Node;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The real TCP implementation of the control channel.
 *
 * <p>Purpose: connect this overlay node to its direct neighbours (as declared
 * in the scenario) over TCP, and turn bytes on those sockets into the
 * {@link ControlMessenger} / {@link ControlMessengerListener} contract, so
 * the routing workstream never touches a socket.</p>
 *
 * <p>Status: milestone M1 of the transport plan
 * ({@code docs/esr-tp2-person-a-transport.md}), built in these steps:
 * <ul>
 *   <li>Step 1 — server: listen on the scenario port, bound to this node's
 *       own address, and accept connections on a dedicated virtual thread.</li>
 *   <li>Step 2 — framing: read and write length-prefixed frames
 *       ({@link WireCodec}) on a connection.</li>
 *   <li>Step 3 — dialing: connect out to every neighbour with a HIGHER id,
 *       retrying until it answers and again after the connection dies.</li>
 *   <li>Step 4 — HELLO handshake: the dialer sends HELLO with its id, the
 *       acceptor validates it and answers with its own HELLO; only then is
 *       the connection bound to a neighbour id. HELLOs never reach the
 *       listener.</li>
 *   <li>Step 5 — registry, sending and events: one {@link Connection} per
 *       live neighbour, each with its own outbox and writer thread; listener
 *       events delivered one at a time.</li>
 * </ul>
 * Death detection by EOF/IOException and automatic redial are already in
 * place. M2 adds a GOODBYE on graceful shutdown: {@link #close()} sends it to
 * every neighbour before closing, and a received GOODBYE ends the connection
 * as a clean leave ("left") rather than a loss ("lost"). The listener sees
 * {@code onNeighborDown} in both cases; the difference shows in the log.
 * M2 also adds a KEEPALIVE: every neighbour is sent one each period (10 s by
 * default), and a neighbour silent for 3 periods is declared lost. This
 * catches what EOF cannot: a cut link with both processes alive (a half-open
 * connection). KEEPALIVEs never reach the listener.</p>
 *
 * <p>Tie-break: for each pair of neighbours only the node with the lower id
 * connects; the higher id only accepts. So there is exactly one TCP
 * connection per overlay link, and both ends know who created it without
 * agreeing on anything at run time.</p>
 *
 * <p>Threading: one virtual thread runs the accept loop, one per higher-id
 * neighbour runs its dial loop, and each live connection has one reader and
 * one writer virtual thread. Only the writer thread ever writes to a socket
 * after the handshake, so frames of concurrent senders never interleave, and
 * {@code send} only enqueues and never blocks: a slow or stalled neighbour
 * delays its own queue, not the flooding to the others (failure mode FM-11).
 * Listener callbacks are serialised by {@code eventLock}, so the listener is
 * never called concurrently and needs no synchronisation of its own; it may
 * call {@code send} from inside a callback.</p>
 *
 * <p>Failure modes: a listener that throws is logged and does not tear the
 * connection down — over real TCP a listener bug must not look like a dead
 * neighbour. This deliberately differs from {@link LoopbackMessenger}, where
 * the exception propagates to the test.</p>
 */
public final class TcpControlMessenger implements ControlMessenger {

    /** How long a single connect attempt may take before it is abandoned. */
    static final int CONNECT_TIMEOUT_MILLIS = 2_000;

    /** Pause between dial attempts, and after a connection is lost. */
    static final long REDIAL_DELAY_MILLIS = 1_000;

    /** How long a peer may take to complete the HELLO handshake. */
    static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;

    /** How long close() waits, in total, for the GOODBYEs to leave before closing the sockets. */
    static final long GOODBYE_FLUSH_MILLIS = 500;

    /** Default KEEPALIVE period: every neighbour is sent one this often. */
    public static final long DEFAULT_KEEPALIVE_INTERVAL_MILLIS = 10_000;

    /** Default number of periods of silence after which a neighbour is declared lost. */
    public static final int DEFAULT_KEEPALIVE_MISSES = 3;

    private final RoutingTable table;
    private final int port;
    private final long keepaliveIntervalMillis;
    private final int keepaliveMisses;
    private final long silenceLimitNanos;
    private volatile ControlMessengerListener listener;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private ServerSocket serverSocket;
    private final AtomicLong nextMessageId = new AtomicLong();

    /** The live, handshaken connection of each neighbour id (step 5). */
    private final Map<Integer, Connection> connections = new ConcurrentHashMap<>();

    /** Every socket open in any phase (handshaking or live), so close() can shut them. */
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();

    /** Serialises registry changes and listener callbacks (step 5). */
    private final Object eventLock = new Object();

    /**
     * Purpose: create the messenger of the node {@code table.self()}.
     *
     * @param table the local view of the scenario: this node and its direct
     *              neighbours
     * @param port  the TCP port shared by every node of the scenario
     */
    public TcpControlMessenger(RoutingTable table, int port) {
        this(table, port, DEFAULT_KEEPALIVE_INTERVAL_MILLIS, DEFAULT_KEEPALIVE_MISSES);
    }

    /**
     * Purpose: same as {@link #TcpControlMessenger(RoutingTable, int)} with
     * explicit KEEPALIVE timing — for tests, or a demo that must detect a cut
     * link faster than the 30 s default.
     *
     * @param keepaliveIntervalMillis how often a KEEPALIVE is sent to each
     *                                neighbour (also how often silence is checked)
     * @param keepaliveMisses         how many periods of silence declare a
     *                                neighbour lost (at least 2, so one late
     *                                KEEPALIVE is tolerated)
     */
    public TcpControlMessenger(RoutingTable table, int port,
                               long keepaliveIntervalMillis, int keepaliveMisses) {
        if (keepaliveIntervalMillis <= 0) {
            throw new IllegalArgumentException("keepalive interval must be positive: " + keepaliveIntervalMillis);
        }
        if (keepaliveMisses < 2) {
            throw new IllegalArgumentException("keepalive misses must be at least 2: " + keepaliveMisses);
        }
        this.table = Objects.requireNonNull(table, "table");
        this.port = port;
        this.keepaliveIntervalMillis = keepaliveIntervalMillis;
        this.keepaliveMisses = keepaliveMisses;
        this.silenceLimitNanos = keepaliveIntervalMillis * keepaliveMisses * 1_000_000;
    }

    // ------------------------------------------------------------------
    // Step 1 — server
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Binds the server socket to this node's own address from the
     * scenario, not to every interface: in CORE a node may have several
     * interfaces, and on a single machine distinct loopback addresses
     * ({@code 127.0.0.1}, {@code 127.0.0.2}, ...) let several nodes share the
     * one scenario port without clashing. Then starts accepting, and dials
     * the higher-id neighbours.</p>
     *
     * @throws UncheckedIOException if the address/port cannot be bound
     */
    @Override
    public synchronized void start() {
        if (lifecycle != Lifecycle.NEW) {
            throw new IllegalStateException("messenger already " + lifecycle.name().toLowerCase());
        }
        InetSocketAddress localAddress = new InetSocketAddress(table.self().address(), port);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);   // allow an immediate restart of a killed node
            serverSocket.bind(localAddress);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot listen on " + localAddress, cause);
        }
        lifecycle = Lifecycle.STARTED;
        log("listening on " + localAddress);

        Thread.ofVirtual().name("accept-" + table.self().id()).start(this::acceptLoop);
        Thread.ofVirtual().name("keepalive-" + table.self().id()).start(this::keepaliveLoop);

        // Step 3: dial only the neighbours with a higher id (tie-break).
        for (Node neighbour : table.neighbours().values()) {
            if (neighbour.id() > table.self().id()) {
                Thread.ofVirtual().name("dial-" + neighbour.id()).start(() -> dialLoop(neighbour));
            }
        }
    }

    /**
     * Purpose: accept connections forever, handing each one to its own
     * thread so that a slow or silent peer never blocks the others.
     */
    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().start(() -> serveAccepted(socket));
            } catch (IOException cause) {
                // accept() fails when the server socket is closed: normal shutdown.
                if (!serverSocket.isClosed()) {
                    log("accept failed: " + cause.getMessage());
                }
            }
        }
    }

    /**
     * Purpose: serve one accepted connection: identify the peer with the
     * HELLO handshake (step 4), then run it until it dies.
     */
    private void serveAccepted(Socket socket) {
        openSockets.add(socket);
        int peerId = handshakeAsAcceptor(socket);
        if (peerId < 0) {
            openSockets.remove(socket);
            closeQuietly(socket);
            return;
        }
        runConnection(socket, peerId);
    }

    /**
     * Purpose: run one handshaken connection, accepted or dialed — after the
     * handshake both directions are symmetric. Registers it (neighbour up),
     * reads frames until it dies, then unregisters it (neighbour down).
     */
    private void runConnection(Socket socket, int peerId) {
        Connection connection = new Connection(peerId, socket);
        try {
            if (!register(connection)) {
                return;   // messenger closing
            }
            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (true) {
                ControlMessage message = readFrame(in);
                connection.heardNow();   // any frame proves the neighbour is alive
                if (message.type() == MessageType.KEEPALIVE) {
                    continue;            // transport-only: never reaches the listener
                }
                if (message.type() == MessageType.GOODBYE) {
                    // A graceful leave: the finally below takes the usual down path.
                    log("neighbour " + peerId + " left: GOODBYE received");
                    return;
                }
                deliver(connection, message);
            }
        } catch (EOFException eof) {
            // While we are closing ourselves, an EOF is just the peer answering our GOODBYE.
            if (lifecycle == Lifecycle.STARTED && !connection.timedOut) {
                log("neighbour " + peerId + " lost: connection closed by the peer");
            }
        } catch (IOException | IllegalArgumentException broken) {
            // A KEEPALIVE timeout closes the socket itself and has already logged why.
            if (lifecycle == Lifecycle.STARTED && !connection.timedOut) {
                log("neighbour " + peerId + " lost: " + broken.getMessage());
            }
        } finally {
            unregister(connection);
            connection.shutdown();
            openSockets.remove(socket);
        }
    }

    // ------------------------------------------------------------------
    // Step 2 — framing
    // ------------------------------------------------------------------

    /**
     * Purpose: read exactly one frame from the stream and decode it.
     *
     * <p>TCP is a byte stream, not a message stream: one read may return
     * half a message or two messages glued together. The 4-byte length
     * prefix fixes that: {@code readInt} reads exactly the length and
     * {@code readFully} blocks until exactly that many body bytes arrived,
     * leaving the next frame untouched in the stream.</p>
     *
     * @throws EOFException             if the peer closed the connection,
     *                                  even in the middle of a frame (the
     *                                  partial frame is discarded)
     * @throws IOException              if the connection broke
     * @throws IllegalArgumentException if the length is negative or larger
     *                                  than {@link WireCodec#MAX_FRAME_BODY_BYTES}
     *                                  (checked BEFORE allocating, so garbage
     *                                  cannot exhaust memory), or the body is
     *                                  not a valid message
     */
    static ControlMessage readFrame(DataInputStream in) throws IOException {
        int bodyLength = in.readInt();
        if (bodyLength < 0 || bodyLength > WireCodec.MAX_FRAME_BODY_BYTES) {
            throw new IllegalArgumentException("invalid frame length: " + bodyLength);
        }
        byte[] body = new byte[bodyLength];
        in.readFully(body);

        // WireCodec.decode expects the whole frame, prefix included.
        byte[] frame = ByteBuffer.allocate(4 + bodyLength).putInt(bodyLength).put(body).array();
        return WireCodec.decode(frame);
    }

    /**
     * Purpose: write one message as a complete frame and push it onto the
     * wire immediately. Used only during the handshake, before the
     * connection's writer thread exists.
     *
     * @throws IOException if the connection broke
     */
    static void writeFrame(OutputStream out, ControlMessage message) throws IOException {
        out.write(WireCodec.encode(message));
        out.flush();
    }

    // ------------------------------------------------------------------
    // Step 3 — dialing
    // ------------------------------------------------------------------

    /**
     * Purpose: keep one connection to {@code neighbour} alive for the whole
     * run. Retrying makes the start order of the nodes irrelevant, and
     * redialing after a loss lets a restarted neighbour rejoin by itself
     * (dynamic join).
     */
    private void dialLoop(Node neighbour) {
        InetSocketAddress remote = new InetSocketAddress(neighbour.address(), port);
        boolean reportedWaiting = false;
        while (lifecycle == Lifecycle.STARTED) {
            Socket socket = new Socket();
            try {
                // Bind our side to our own scenario address, so the peer sees
                // the address declared in the YAML as the source.
                socket.bind(new InetSocketAddress(table.self().address(), 0));
                socket.connect(remote, CONNECT_TIMEOUT_MILLIS);
            } catch (IOException unreachable) {
                closeQuietly(socket);
                if (!reportedWaiting) {
                    log("neighbour " + neighbour.id() + " at " + remote
                            + " not reachable yet, retrying every " + REDIAL_DELAY_MILLIS + " ms");
                    reportedWaiting = true;
                }
                sleepBeforeRedial();
                continue;
            }
            reportedWaiting = false;
            openSockets.add(socket);
            if (handshakeAsDialer(socket, neighbour.id())) {
                runConnection(socket, neighbour.id());   // blocks until the connection dies
            } else {
                openSockets.remove(socket);
                closeQuietly(socket);
            }
            sleepBeforeRedial();
        }
    }

    /**
     * Purpose: wait before the next dial attempt.
     */
    private void sleepBeforeRedial() {
        try {
            Thread.sleep(REDIAL_DELAY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Step 4 — HELLO handshake
    // ------------------------------------------------------------------

    /**
     * Purpose: dialer side of the handshake. Say who we are, then require
     * the peer to answer with a HELLO carrying the id we expected to reach.
     *
     * <p>The answer matters: a successful {@code connect} only means the
     * peer's OS accepted the connection, not that the peer application
     * accepted us, and the address in the YAML may point at the wrong
     * process. Only after both sides confirmed is the neighbour up.</p>
     *
     * @return true if the peer is {@code expectedId} and accepted us
     */
    private boolean handshakeAsDialer(Socket socket, int expectedId) {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            writeFrame(socket.getOutputStream(), hello());
            ControlMessage reply = readFrame(new DataInputStream(socket.getInputStream()));
            int peerId = helloSender(reply);
            if (peerId != expectedId) {
                log("handshake rejected: expected node " + expectedId + " at "
                        + socket.getRemoteSocketAddress() + " but node " + peerId + " answered");
                return false;
            }
            socket.setSoTimeout(0);   // after the handshake, silence is normal
            return true;
        } catch (IOException | IllegalArgumentException failed) {
            log("handshake with node " + expectedId + " failed: " + failed.getMessage());
            return false;
        }
    }

    /**
     * Purpose: acceptor side of the handshake. Require a HELLO as the first
     * frame, check that its sender is a declared neighbour that is allowed to
     * dial us (lower id, by the tie-break), then answer with our own HELLO.
     *
     * @return the peer's node id, or -1 if the connection must be dropped
     */
    private int handshakeAsAcceptor(Socket socket) {
        try {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            ControlMessage first = readFrame(new DataInputStream(socket.getInputStream()));
            int peerId = helloSender(first);
            if (!table.neighbours().containsKey(peerId)) {
                log("handshake rejected: node " + peerId + " (" + socket.getRemoteSocketAddress()
                        + ") is not a neighbour in the scenario");
                return -1;
            }
            if (peerId > table.self().id()) {
                log("handshake rejected: node " + peerId
                        + " has a higher id and should be dialed, not dial us");
                return -1;
            }
            writeFrame(socket.getOutputStream(), hello());
            socket.setSoTimeout(0);
            return peerId;
        } catch (IOException | IllegalArgumentException failed) {
            log("handshake from " + socket.getRemoteSocketAddress() + " failed: " + failed.getMessage());
            return -1;
        }
    }

    /**
     * Purpose: our HELLO: the payload is our node id.
     */
    private ControlMessage hello() {
        return new ControlMessage(MessageType.HELLO, nextMessageId.getAndIncrement(), null,
                String.valueOf(table.self().id()));
    }

    /**
     * Purpose: the node id carried by a HELLO.
     *
     * @throws IllegalArgumentException if {@code message} is not a HELLO or
     *                                  its payload is not a node id
     */
    private static int helloSender(ControlMessage message) {
        if (message.type() != MessageType.HELLO) {
            throw new IllegalArgumentException("expected HELLO first, got " + message.type());
        }
        try {
            return Integer.parseInt(message.payload());
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException("HELLO payload is not a node id: " + message.payload());
        }
    }

    // ------------------------------------------------------------------
    // Step 5 — registry, sending and listener events
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Only enqueues the frame on the neighbour's outbox; never blocks.</p>
     */
    @Override
    public void send(int destinationNodeId, ControlMessage message) {
        Objects.requireNonNull(message, "message");
        requireStarted();
        if (!table.neighbours().containsKey(destinationNodeId)) {
            throw new IllegalArgumentException(
                    "node " + destinationNodeId + " is not a neighbour of " + table.self().id());
        }
        Connection connection = connections.get(destinationNodeId);
        if (connection == null) {
            throw new IllegalStateException("link to neighbour " + destinationNodeId + " is down");
        }
        connection.enqueue(WireCodec.encode(message));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes once and enqueues the same frame on every connected
     * neighbour except {@code excludedNodeId}; neighbours that are down are
     * simply not in the registry.</p>
     */
    @Override
    public void sendToNeighboursExcept(int excludedNodeId, ControlMessage message) {
        Objects.requireNonNull(message, "message");
        requireStarted();
        byte[] frame = WireCodec.encode(message);
        for (Connection connection : connections.values()) {
            if (connection.peerId != excludedNodeId) {
                connection.enqueue(frame);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setListener(ControlMessengerListener listener) {
        this.listener = listener;
    }

    /**
     * Purpose: the ids of the neighbours whose connection is currently up.
     * Handy for logs and tests.
     */
    public List<Integer> connectedNeighbours() {
        return connections.keySet().stream().sorted().toList();
    }

    /**
     * Purpose: whether the control link to {@code nodeId} is up right now.
     * Cheap enough to call per packet; the UDP data channel uses it to refuse
     * sending to a neighbour that is down.
     */
    public boolean isConnected(int nodeId) {
        return connections.containsKey(nodeId);
    }

    /**
     * Purpose: put a handshaken connection in the registry and fire
     * {@code onNeighborUp}. If the neighbour already had a connection (it
     * restarted while the old one still looked open), the old one is
     * replaced: its {@code onNeighborDown} fires first, so up/down events
     * still alternate.
     *
     * @return false if the messenger is no longer started
     */
    private boolean register(Connection connection) {
        synchronized (eventLock) {
            if (lifecycle != Lifecycle.STARTED) {
                return false;
            }
            Connection stale = connections.put(connection.peerId, connection);
            if (stale != null) {
                log("neighbour " + connection.peerId + " reconnected, dropping the stale connection");
                notifyListener(l -> l.onNeighborDown(connection.peerId));
                stale.shutdown();
            }
            log("neighbour " + connection.peerId + " up (" + connection.socket.getRemoteSocketAddress() + ")");
            notifyListener(l -> l.onNeighborUp(connection.peerId));
            return true;
        }
    }

    /**
     * Purpose: remove a dead connection from the registry and fire
     * {@code onNeighborDown} — only if it is still the registered one, so a
     * replaced or already-removed connection never fires twice.
     */
    private void unregister(Connection connection) {
        synchronized (eventLock) {
            if (connections.remove(connection.peerId, connection)) {
                log("neighbour " + connection.peerId + " down");
                notifyListener(l -> l.onNeighborDown(connection.peerId));
            }
        }
    }

    /**
     * Purpose: hand one received message to the listener. Transport-level
     * messages (a HELLO after the handshake) are consumed here.
     */
    private void deliver(Connection connection, ControlMessage message) {
        synchronized (eventLock) {
            if (connections.get(connection.peerId) != connection) {
                return;   // a replaced connection: ignore what it still delivers
            }
            if (message.type() == MessageType.HELLO) {
                log("ignoring repeated HELLO from neighbour " + connection.peerId);
                return;
            }
            notifyListener(l -> l.onMessage(connection.peerId, message));
        }
    }

    /**
     * Purpose: call the listener, if any, logging instead of propagating
     * its exceptions (see the class comment). Callers hold {@code eventLock}.
     */
    private void notifyListener(java.util.function.Consumer<ControlMessengerListener> event) {
        ControlMessengerListener current = listener;
        if (current == null) {
            return;
        }
        try {
            event.accept(current);
        } catch (RuntimeException bug) {
            log("listener threw " + bug);
        }
    }

    // ------------------------------------------------------------------
    // M2 — KEEPALIVE: detect neighbours that went silent
    // ------------------------------------------------------------------

    /**
     * Purpose: every {@code keepaliveIntervalMillis}, for each live neighbour,
     * either declare it lost (silent longer than the limit) or send it a
     * KEEPALIVE.
     *
     * <p>Why on the receiving side: a {@code write} succeeds as soon as the
     * OS buffers the bytes, and on a cut link TCP keeps retransmitting for
     * up to ~15-30 minutes before reporting an error. Counting silence lets
     * the overlay decide in {@code interval x misses} instead. Both ends send
     * and both watch, so each detects the cut on its own.</p>
     *
     * <p>A lost neighbour is closed here; its reader thread then takes the
     * usual path (unregister, {@code onNeighborDown}, and the dialer of the
     * lower id redials).</p>
     */
    private void keepaliveLoop() {
        while (lifecycle == Lifecycle.STARTED) {
            try {
                Thread.sleep(keepaliveIntervalMillis);
            } catch (InterruptedException interrupted) {
                return;
            }
            if (lifecycle != Lifecycle.STARTED) {
                return;
            }
            byte[] keepalive = WireCodec.encode(new ControlMessage(MessageType.KEEPALIVE,
                    nextMessageId.getAndIncrement(), null, String.valueOf(table.self().id())));
            for (Connection connection : connections.values()) {
                long silence = connection.silenceNanos();
                if (silence > silenceLimitNanos) {
                    connection.timedOut = true;
                    log("neighbour " + connection.peerId + " lost: silent for " + silence / 1_000_000
                            + " ms (no frame in " + keepaliveMisses + " keepalive periods of "
                            + keepaliveIntervalMillis + " ms)");
                    connection.shutdown();   // the reader thread takes the usual down path
                } else {
                    connection.enqueue(keepalive);
                }
            }
        }
    }

    private void requireStarted() {
        if (lifecycle != Lifecycle.STARTED) {
            throw new IllegalStateException("messenger " + lifecycle.name().toLowerCase());
        }
    }

    /**
     * One live, handshaken connection to a neighbour.
     *
     * <p>Owns an outbox and the only thread allowed to write to the socket
     * after the handshake, so frames from concurrent senders are written
     * whole, one after the other, never interleaved.</p>
     */
    private final class Connection {

        /** Marker that tells the writer thread to stop. Compared by identity. */
        private static final byte[] STOP = new byte[0];

        private final int peerId;
        private final Socket socket;
        private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
        private final Thread writer;

        /** {@link System#nanoTime()} of the last frame received; written by the reader, read by the keepalive thread. */
        private volatile long lastHeardNanos = System.nanoTime();

        /** Set by the keepalive thread when it closes this connection for silence, so the reader does not log a second reason. */
        private volatile boolean timedOut;

        private Connection(int peerId, Socket socket) {
            this.peerId = peerId;
            this.socket = socket;
            this.writer = Thread.ofVirtual().name("write-" + peerId).start(this::writeLoop);
        }

        /** Purpose: record that a frame just arrived from this neighbour. */
        private void heardNow() {
            lastHeardNanos = System.nanoTime();
        }

        /** Purpose: how long this neighbour has been silent, in nanoseconds. */
        private long silenceNanos() {
            return System.nanoTime() - lastHeardNanos;
        }

        /** Purpose: queue one complete frame for sending. Never blocks. */
        private void enqueue(byte[] frame) {
            outbox.add(frame);
        }

        /**
         * Purpose: write queued frames in order until stopped. A write
         * failure closes the socket, so the reader thread notices and the
         * neighbour goes down through the usual path.
         */
        private void writeLoop() {
            try {
                OutputStream out = socket.getOutputStream();
                while (true) {
                    byte[] frame = outbox.take();
                    if (frame == STOP) {
                        return;
                    }
                    out.write(frame);
                    out.flush();
                }
            } catch (IOException | InterruptedException failed) {
                closeQuietly(socket);
            }
        }

        /** Purpose: stop the writer and close the socket. Idempotent. */
        private void shutdown() {
            outbox.add(STOP);
            closeQuietly(socket);
        }

        /**
         * Purpose: first half of a graceful close — queue {@code goodbye}
         * behind everything already queued, then STOP, so the writer sends
         * them all and exits. The socket stays open until
         * {@link #awaitFlushThenClose(long)}.
         */
        private void sayGoodbye(byte[] goodbye) {
            outbox.add(goodbye);
            outbox.add(STOP);
        }

        /**
         * Purpose: second half of a graceful close — wait until
         * {@code deadlineNanos} (a {@link System#nanoTime()} value) for the
         * writer to drain the outbox, then close the socket whatever happened.
         */
        private void awaitFlushThenClose(long deadlineNanos) {
            long remainingMillis = Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000);
            try {
                writer.join(Math.max(1, remainingMillis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            closeQuietly(socket);
        }
    }

    // ------------------------------------------------------------------
    // Shutdown and GOODBYE (M2)
    // ------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Stops accepting and dialing, fires {@code onNeighborDown} locally
     * for every live neighbour, queues a GOODBYE on every connection, waits
     * up to {@link #GOODBYE_FLUSH_MILLIS} in total for the writers to send
     * them, then closes every socket. Peers log a clean leave and fire their
     * own {@code onNeighborDown}; a peer that misses the GOODBYE still sees
     * the EOF.</p>
     */
    @Override
    public void close() {
        synchronized (this) {
            if (lifecycle == Lifecycle.CLOSED) {
                return;
            }
            lifecycle = Lifecycle.CLOSED;
        }
        List<Connection> leaving;
        synchronized (eventLock) {
            leaving = List.copyOf(connections.values());
            byte[] goodbye = WireCodec.encode(new ControlMessage(MessageType.GOODBYE,
                    nextMessageId.getAndIncrement(), null, String.valueOf(table.self().id())));
            for (Connection connection : leaving) {
                connections.remove(connection.peerId);
                notifyListener(l -> l.onNeighborDown(connection.peerId));
                connection.sayGoodbye(goodbye);
            }
        }
        // Outside the lock: give the writers a moment to put the GOODBYEs on
        // the wire, then close. One shared deadline, not one per neighbour.
        long deadline = System.nanoTime() + GOODBYE_FLUSH_MILLIS * 1_000_000;
        for (Connection connection : leaving) {
            connection.awaitFlushThenClose(deadline);
        }
        for (Socket socket : openSockets) {
            closeQuietly(socket);
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    /**
     * Purpose: one log line, prefixed with this node's id, readable in a
     * CORE terminal.
     */
    private void log(String text) {
        System.err.println("[node " + table.self().id() + "] " + text);
    }

    /** The lifecycle states of a messenger. */
    private enum Lifecycle {
        NEW, STARTED, CLOSED
    }
}
