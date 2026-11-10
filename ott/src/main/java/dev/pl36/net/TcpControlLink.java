package dev.pl36.net;

import dev.pl36.net.api.ControlMessenger;
import dev.pl36.net.api.ControlMessengerListener;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The minimal real implementation of {@link ControlMessenger}: one-shot TCP
 * connections framed with {@link WireCodec}, for the localhost placeholder
 * until the transport workstream lands its full neighbour stack.
 *
 * <p>Purpose: let the CLI server and client processes talk WANT_FLOW /
 * LEAVE_FLOW / ANNOUNCE over real TCP on localhost. Two shapes:
 * {@link #listen(int, int, int)} accepts any number of peers and assigns
 * each one a synthetic node id counting up from {@code firstPeerNodeId};
 * {@link #connect(int, int, InetSocketAddress)} opens exactly one connection
 * to a known peer id.</p>
 *
 * <p>Wire format: exactly {@link WireCodec#encode(ControlMessage)} — a
 * 4-byte big-endian body length followed by the JSON body — per frame, back
 * to back on the stream.</p>
 *
 * <p>Invariants: {@link #start()} precedes every send and every event;
 * {@link #close()} is idempotent and closes every socket; reader and accept
 * threads are virtual daemon threads, so they never keep the JVM alive.
 * Connections are keyed by peer node id; {@link #peerAddress(int)} exposes
 * the TCP address of a peer so the composition root can register the peer's
 * <em>data</em> endpoint on the {@code DataChannel}.</p>
 *
 * <p>Failure modes: the factories throw {@link IOException} if the port
 * cannot be bound or the peer cannot be reached; {@link #send} throws
 * {@link IllegalArgumentException} for an unknown destination and
 * {@link IllegalStateException} before {@code start()}, after
 * {@code close()}, or when the link is down; a malformed frame from a peer
 * is logged and skipped. A connection that ends — EOF, reset, or
 * {@link #close()} — fires {@code onNeighborDown} exactly once, on both
 * sides (the peer sees it as EOF).</p>
 */
public final class TcpControlLink implements ControlMessenger {

    private final int ownNodeId;
    private final ServerSocket serverSocket;
    private final Socket initialSocket;
    private final int fixedPeerNodeId;
    private final AtomicInteger nextPeerNodeId;
    private final Map<Integer, Connection> connectionsByPeerId = new ConcurrentHashMap<>();
    private volatile ControlMessengerListener listener;
    private volatile boolean started;
    private volatile boolean closed;

    private TcpControlLink(int ownNodeId,
                           ServerSocket serverSocket,
                           Socket initialSocket,
                           int fixedPeerNodeId,
                           int firstPeerNodeId) {
        this.ownNodeId = ownNodeId;
        this.serverSocket = serverSocket;
        this.initialSocket = initialSocket;
        this.fixedPeerNodeId = fixedPeerNodeId;
        this.nextPeerNodeId = new AtomicInteger(firstPeerNodeId);
    }

    /**
     * Purpose: the accepting side — bind {@code controlPort} and accept any
     * number of peers, assigning synthetic node ids from
     * {@code firstPeerNodeId} upwards.
     *
     * @param ownNodeId        the local node id (synthetic in the placeholder)
     * @param controlPort      the TCP port to listen on
     * @param firstPeerNodeId  the first synthetic id given to an accepted peer
     * @return the link, not yet started
     * @throws IOException if the port cannot be bound
     */
    public static TcpControlLink listen(int ownNodeId, int controlPort, int firstPeerNodeId)
            throws IOException {
        return new TcpControlLink(ownNodeId, new ServerSocket(controlPort), null, -1, firstPeerNodeId);
    }

    /**
     * Purpose: the dialing side — open one TCP connection to
     * {@code peerAddress} and register it under {@code peerNodeId}.
     *
     * @param ownNodeId  the local node id (synthetic in the placeholder)
     * @param peerNodeId the node id of the peer being dialed
     * @param peerAddress the peer's control address
     * @return the link, not yet started
     * @throws IOException if the connection cannot be established
     */
    public static TcpControlLink connect(int ownNodeId, int peerNodeId, InetSocketAddress peerAddress)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(peerAddress);
        return new TcpControlLink(ownNodeId, null, socket, peerNodeId, -1);
    }

    /**
     * Purpose: the TCP remote address of the connection registered under
     * {@code nodeId} — the composition root uses it to derive the peer's
     * data endpoint (host from here, port from the WANT_FLOW payload).
     *
     * @throws IllegalArgumentException if no connection is registered under
     *                                  {@code nodeId}
     */
    public InetSocketAddress peerAddress(int nodeId) {
        Connection connection = connectionsByPeerId.get(nodeId);
        if (connection == null) {
            throw new IllegalArgumentException("unknown peer node id: " + nodeId);
        }
        return (InetSocketAddress) connection.socket().getRemoteSocketAddress();
    }

    /** {@inheritDoc} */
    @Override
    public void send(int destinationNodeId, ControlMessage message) {
        Objects.requireNonNull(message, "message");
        requireStarted();
        Connection connection = connectionsByPeerId.get(destinationNodeId);
        if (connection == null) {
            throw new IllegalArgumentException("unknown destination node id: " + destinationNodeId);
        }
        byte[] frame = WireCodec.encode(message);
        try {
            synchronized (connection) {
                OutputStream out = connection.socket().getOutputStream();
                out.write(frame);
                out.flush();
            }
        } catch (IOException sendFailure) {
            throw new IllegalStateException(
                    "control link to " + destinationNodeId + " is down: " + sendFailure.getMessage(),
                    sendFailure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Broken links are silently skipped, mirroring
     * {@code LoopbackMessenger}.</p>
     */
    @Override
    public void sendToNeighboursExcept(int excludedNodeId, ControlMessage message) {
        Objects.requireNonNull(message, "message");
        requireStarted();
        for (Map.Entry<Integer, Connection> entry : connectionsByPeerId.entrySet()) {
            if (entry.getKey() == excludedNodeId) {
                continue;
            }
            try {
                send(entry.getKey(), message);
            } catch (RuntimeException linkDown) {
                // silently skipped, as on a network with a dead link
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setListener(ControlMessengerListener listener) {
        this.listener = listener;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starts the accept thread (listening side) or the reader thread
     * (dialing side), and fires {@code onNeighborUp} for every connection
     * already established.</p>
     */
    @Override
    public void start() {
        if (closed) {
            throw new IllegalStateException("control link closed: " + ownNodeId);
        }
        if (started) {
            throw new IllegalStateException("control link already started: " + ownNodeId);
        }
        started = true;
        if (serverSocket != null) {
            Thread.ofVirtual().name("tcp-control-accept-" + ownNodeId).start(this::acceptLoop);
        } else {
            registerConnection(fixedPeerNodeId, initialSocket);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fires {@code onNeighborDown} locally for every open connection; the
     * peers observe the closed sockets as EOF and fire it on their side.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        for (Map.Entry<Integer, Connection> entry : connectionsByPeerId.entrySet()) {
            closeConnection(entry.getValue());
            fireNeighborDown(entry.getKey());
        }
        connectionsByPeerId.clear();
    }

    /**
     * Purpose: accept peers until {@link #close()}, registering each under
     * the next synthetic node id.
     */
    private void acceptLoop() {
        while (!closed) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException acceptFailure) {
                return; // closed
            }
            registerConnection(nextPeerNodeId.getAndIncrement(), socket);
        }
    }

    /**
     * Purpose: register one connection, fire {@code onNeighborUp}, and start
     * its reader thread.
     */
    private void registerConnection(int peerNodeId, Socket socket) {
        Connection connection = new Connection(peerNodeId, socket);
        connectionsByPeerId.put(peerNodeId, connection);
        fireNeighborUp(peerNodeId);
        Thread.ofVirtual()
                .name("tcp-control-reader-" + ownNodeId + "-" + peerNodeId)
                .start(() -> receiveLoop(connection));
    }

    /**
     * Purpose: read length-framed messages from one connection until it ends,
     * handing each decoded message to the listener. On EOF or failure the
     * connection is removed and {@code onNeighborDown} fires once.
     */
    private void receiveLoop(Connection connection) {
        try {
            InputStream in = connection.socket().getInputStream();
            while (!closed) {
                byte[] frame = readFrame(in);
                if (frame == null) {
                    break; // clean EOF
                }
                final ControlMessage message;
                try {
                    message = WireCodec.decode(frame);
                } catch (IllegalArgumentException malformed) {
                    System.out.printf("control link %d: dropping malformed frame from %d: %s%n",
                            ownNodeId, connection.peerNodeId(), malformed.getMessage());
                    continue;
                }
                ControlMessengerListener current = listener;
                if (current != null) {
                    current.onMessage(connection.peerNodeId(), message);
                }
            }
        } catch (IOException receiveFailure) {
            if (!closed) {
                System.out.printf("control link %d: connection to %d failed: %s%n",
                        ownNodeId, connection.peerNodeId(), receiveFailure.getMessage());
            }
        } finally {
            if (connectionsByPeerId.remove(connection.peerNodeId(), connection)) {
                closeConnection(connection);
                fireNeighborDown(connection.peerNodeId());
            }
        }
    }

    /**
     * Purpose: read exactly one length-framed message from {@code in}.
     *
     * @return the frame bytes, or {@code null} on a clean EOF before any
     *         byte of the next frame
     * @throws IOException on a truncated frame or an oversized length prefix
     */
    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] lengthPrefix = in.readNBytes(4);
        if (lengthPrefix.length == 0) {
            return null;
        }
        if (lengthPrefix.length < 4) {
            throw new EOFException("truncated frame length prefix: " + lengthPrefix.length + " bytes");
        }
        int bodyLength = ((lengthPrefix[0] & 0xFF) << 24)
                | ((lengthPrefix[1] & 0xFF) << 16)
                | ((lengthPrefix[2] & 0xFF) << 8)
                | (lengthPrefix[3] & 0xFF);
        if (bodyLength < 0 || bodyLength > WireCodec.MAX_FRAME_BODY_BYTES) {
            throw new IOException("invalid frame body length: " + bodyLength);
        }
        byte[] body = in.readNBytes(bodyLength);
        if (body.length < bodyLength) {
            throw new EOFException("truncated frame body: expected " + bodyLength + ", got " + body.length);
        }
        byte[] frame = new byte[4 + bodyLength];
        System.arraycopy(lengthPrefix, 0, frame, 0, 4);
        System.arraycopy(body, 0, frame, 4, bodyLength);
        return frame;
    }

    /** Purpose: enforce the started-and-not-closed lifecycle on sends. */
    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("control link not started: " + ownNodeId);
        }
        if (closed) {
            throw new IllegalStateException("control link closed: " + ownNodeId);
        }
    }

    /** Purpose: close one connection's socket, ignoring failures. */
    private static void closeConnection(Connection connection) {
        try {
            connection.socket().close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    /** Purpose: fire {@code onNeighborUp} if started and a listener is set. */
    private void fireNeighborUp(int peerNodeId) {
        if (started && listener != null) {
            listener.onNeighborUp(peerNodeId);
        }
    }

    /** Purpose: fire {@code onNeighborDown} if started and a listener is set. */
    private void fireNeighborDown(int peerNodeId) {
        if (started && listener != null) {
            listener.onNeighborDown(peerNodeId);
        }
    }

    /** One TCP connection to a peer, keyed by the peer's node id. */
    private record Connection(int peerNodeId, Socket socket) {
    }
}
