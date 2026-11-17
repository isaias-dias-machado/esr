package dev.pl36.net;

import dev.pl36.net.api.DataChannel;
import dev.pl36.net.api.DataChannelListener;
import dev.pl36.routing.RoutingTable;
import dev.pl36.scenario.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * The real UDP implementation of the data channel, for stream packets.
 *
 * <p>Purpose: carry raw datagrams (video packets laid out by
 * {@link DataPacketHeader}) between this overlay node and its direct
 * neighbours from the scenario, behind the {@link DataChannel} /
 * {@link DataChannelListener} contract. It is the data-side twin of
 * {@link TcpControlMessenger}, and the intended replacement of the
 * {@code UdpDataChannel} placeholder: neighbours and their addresses come from
 * the scenario, not from peers registered at run time.</p>
 *
 * <p>How it works:
 * <ul>
 *   <li>One {@link DatagramSocket}, bound to this node's own scenario address
 *       and the scenario port (TCP and UDP may share a port number). It is
 *       used both to receive and to send, so every packet leaves from
 *       {@code <scenario address>:<port>} — which is what lets the receiver
 *       recognise the sender.</li>
 *   <li>Sender identification: UDP has no connection and no HELLO, so a
 *       datagram is attributed to a neighbour by its source IP, looked up in a
 *       map built from the scenario ({@code neighbour IP -> node id}).
 *       Datagrams from any other source are dropped (logged once per
 *       source).</li>
 *   <li>Link state: UDP cannot tell whether a neighbour is alive, so the
 *       constructor takes a {@code linkUp} test — in practice
 *       {@link TcpControlMessenger#isConnected(int)} — and sending to a
 *       neighbour whose control link is down is refused, as the contract
 *       requires.</li>
 * </ul></p>
 *
 * <p>Threading: one virtual thread receives and delivers, so the listener is
 * never called concurrently. Senders need no queue: each
 * {@link DatagramSocket#send} puts one whole datagram on the wire, so packets
 * from concurrent senders never interleave, and a send never waits for the
 * receiver.</p>
 *
 * <p>Failure modes: packet loss, reordering and duplication are UDP's normal
 * behaviour and are not reported; the channel neither inspects nor repairs
 * packets (selective retransmission, if any, belongs to the streaming
 * layer). {@link #sendDatagram} throws {@link IllegalArgumentException} for a
 * non-neighbour or an oversized packet, and {@link IllegalStateException}
 * before {@link #start()}, after {@link #close()}, or when the link is down.
 * A listener that throws is logged and does not stop reception (unlike
 * {@link LoopbackMessenger}, where it propagates to the test).</p>
 */
public final class UdpDataTransport implements DataChannel {

    /** Largest UDP payload over IPv4: 65 535 - 20 (IP header) - 8 (UDP header). */
    public static final int MAX_DATAGRAM_BYTES = 65_507;

    /** Receive buffer asked of the OS, to absorb short bursts (the OS may grant less). */
    static final int RECEIVE_BUFFER_BYTES = 1 << 20;

    private final RoutingTable table;
    private final int port;
    private final IntPredicate linkUp;
    private final Map<InetAddress, Integer> neighbourIdByAddress;
    private final Set<InetAddress> reportedUnknownSources = ConcurrentHashMap.newKeySet();
    private volatile DataChannelListener listener;
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private DatagramSocket socket;

    /**
     * Purpose: create the data channel of the node {@code table.self()}.
     *
     * @param table  the local view of the scenario: this node and its direct
     *               neighbours
     * @param port   the UDP port shared by every node of the scenario
     * @param linkUp tells whether the control link to a neighbour is up;
     *               typically {@code tcpControlMessenger::isConnected}
     */
    public UdpDataTransport(RoutingTable table, int port, IntPredicate linkUp) {
        this.table = Objects.requireNonNull(table, "table");
        this.port = port;
        this.linkUp = Objects.requireNonNull(linkUp, "linkUp");

        // The reverse map used to attribute inbound datagrams: neighbour IP -> id.
        Map<InetAddress, Integer> byAddress = new HashMap<>();
        for (Node neighbour : table.neighbours().values()) {
            Integer clash = byAddress.put(neighbour.address(), neighbour.id());
            if (clash != null) {
                throw new IllegalArgumentException("neighbours " + clash + " and " + neighbour.id()
                        + " share the address " + neighbour.address()
                        + ": UDP cannot tell their packets apart");
            }
        }
        this.neighbourIdByAddress = Map.copyOf(byAddress);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Binds the socket to this node's own scenario address and port, then
     * starts the receiving thread.</p>
     *
     * @throws UncheckedIOException if the address/port cannot be bound
     */
    @Override
    public synchronized void start() {
        if (lifecycle != Lifecycle.NEW) {
            throw new IllegalStateException("data channel already " + lifecycle.name().toLowerCase());
        }
        InetSocketAddress localAddress = new InetSocketAddress(table.self().address(), port);
        int receiveBuffer;
        try {
            socket = new DatagramSocket(localAddress);
            // Datagrams that arrive while the buffer is full are dropped by the
            // OS; a bigger buffer absorbs short bursts (several flows, replication).
            // The OS may grant less than asked (Linux caps it at net.core.rmem_max).
            socket.setReceiveBufferSize(RECEIVE_BUFFER_BYTES);
            receiveBuffer = socket.getReceiveBufferSize();
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot bind UDP " + localAddress, cause);
        }
        lifecycle = Lifecycle.STARTED;
        log("UDP data channel on " + localAddress + " (receive buffer " + receiveBuffer / 1024 + " KB)");
        Thread.ofVirtual().name("udp-receive-" + table.self().id()).start(this::receiveLoop);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends one datagram from this node's scenario address to the
     * neighbour's scenario address. Never blocks waiting for the receiver.</p>
     */
    @Override
    public void sendDatagram(int destinationNodeId, byte[] packet) {
        Objects.requireNonNull(packet, "packet");
        if (lifecycle != Lifecycle.STARTED) {
            throw new IllegalStateException("data channel " + lifecycle.name().toLowerCase());
        }
        Node neighbour = table.neighbours().get(destinationNodeId);
        if (neighbour == null) {
            throw new IllegalArgumentException(
                    "node " + destinationNodeId + " is not a neighbour of " + table.self().id());
        }
        if (packet.length > MAX_DATAGRAM_BYTES) {
            throw new IllegalArgumentException("packet of " + packet.length
                    + " bytes exceeds the UDP maximum of " + MAX_DATAGRAM_BYTES
                    + "; split it before sending");
        }
        if (!linkUp.test(destinationNodeId)) {
            throw new IllegalStateException("link to neighbour " + destinationNodeId + " is down");
        }
        try {
            socket.send(new DatagramPacket(packet, packet.length, neighbour.address(), port));
        } catch (IOException cause) {
            // Closed concurrently, or the OS refused it: report as an unusable channel.
            throw new IllegalStateException("cannot send to neighbour " + destinationNodeId
                    + ": " + cause.getMessage(), cause);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setListener(DataChannelListener listener) {
        this.listener = listener;
    }

    /**
     * Purpose: receive datagrams until the socket is closed, attribute each
     * to a neighbour by its source IP, and hand it to the listener.
     */
    private void receiveLoop() {
        // One reusable buffer; each delivered packet gets its own exact-size copy.
        byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (lifecycle == Lifecycle.STARTED) {
            try {
                datagram.setLength(buffer.length);
                socket.receive(datagram);   // blocks until a datagram arrives
            } catch (IOException cause) {
                if (lifecycle == Lifecycle.STARTED) {
                    log("UDP receive failed: " + cause.getMessage());
                }
                continue;   // closing: the while condition ends the loop
            }
            InetAddress source = datagram.getAddress();
            Integer fromNodeId = neighbourIdByAddress.get(source);
            if (fromNodeId == null) {
                if (reportedUnknownSources.add(source)) {
                    log("dropping UDP datagrams from " + source + ": not a neighbour in the scenario");
                }
                continue;
            }
            byte[] packet = Arrays.copyOfRange(buffer, datagram.getOffset(),
                    datagram.getOffset() + datagram.getLength());
            deliver(fromNodeId, packet);
        }
    }

    /**
     * Purpose: call the listener, if any, logging instead of propagating its
     * exceptions so one bad packet never stops reception.
     */
    private void deliver(int fromNodeId, byte[] packet) {
        DataChannelListener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onDatagram(fromNodeId, packet);
        } catch (RuntimeException bug) {
            log("data listener threw " + bug);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closing the socket wakes the receiving thread, which then exits.</p>
     */
    @Override
    public void close() {
        synchronized (this) {
            if (lifecycle == Lifecycle.CLOSED) {
                return;
            }
            lifecycle = Lifecycle.CLOSED;
        }
        if (socket != null) {
            socket.close();
        }
    }

    /**
     * Purpose: one log line, prefixed with this node's id, in the same format
     * as {@link TcpControlMessenger}.
     */
    private void log(String text) {
        System.err.println("[node " + table.self().id() + "] " + text);
    }

    /** The lifecycle states of a data channel. */
    private enum Lifecycle {
        NEW, STARTED, CLOSED
    }
}
