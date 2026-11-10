package dev.pl36.net;

import dev.pl36.net.api.DataChannel;
import dev.pl36.net.api.DataChannelListener;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The minimal real implementation of {@link DataChannel} over UDP: one
 * {@link DatagramSocket} and a fixed set of peers.
 *
 * <p>Purpose: let the one-server/one-client streaming demo run as two real
 * processes on localhost before the full transport workstream lands. It is a
 * placeholder for that workstream, not the final transport: there is no
 * neighbour discovery, no reconnect, and inbound senders are resolved to a
 * node id by source address only.</p>
 *
 * <p>Wire format: exactly {@link DataPacketHeader#encode(DataPacketHeader)} —
 * the channel is a dumb pipe and does not inspect datagrams.</p>
 *
 * <p>Invariants: a {@code listenPort} of 0 binds an ephemeral port (server
 * side); peers can be added at any time before {@link #close()} with
 * {@link #addPeer(int, InetSocketAddress)}, which is how the placeholder
 * server registers each requester on the one shared channel;
 * {@link #start()} precedes every send and every listener event;
 * {@link #close()} is idempotent and stops the receiver thread; the receiver
 * thread is a daemon, so it never keeps the JVM alive on its own.</p>
 *
 * <p>Failure modes: the constructor throws {@link SocketException} if the port
 * cannot be bound; {@link #sendDatagram} throws {@link IllegalArgumentException}
 * for a destination id that is not in the peer map and {@link IllegalStateException}
 * before {@code start()} or after {@code close()}, matching the
 * {@link DataChannel} contract; inbound datagrams from unknown sources are
 * delivered with {@code fromNodeId} equal to {@link #UNKNOWN_NODE_ID}.</p>
 */
public final class UdpDataChannel implements DataChannel {

    /** Datagram buffer size: the largest UDP payload that can exist. */
    private static final int MAX_DATAGRAM_BYTES = 65535;

    /** Node id reported for inbound datagrams whose source is not a known peer. */
    public static final int UNKNOWN_NODE_ID = -1;

    private final DatagramSocket socket;
    private final Map<Integer, InetSocketAddress> peersByNodeId = new ConcurrentHashMap<>();
    private final Map<String, Integer> nodeIdBySourceKey = new ConcurrentHashMap<>();
    private volatile DataChannelListener listener;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * Purpose: create a channel bound to {@code listenPort} that can send to
     * the given peers.
     *
     * @param listenPort the local UDP port to bind, 0 for ephemeral
     * @param peers      destination node id to socket address mapping
     * @throws SocketException if the port cannot be bound
     */
    public UdpDataChannel(int listenPort, Map<Integer, InetSocketAddress> peers) throws SocketException {
        this.socket = new DatagramSocket(listenPort);
        peers.forEach(this::addPeer);
    }

    /**
     * Purpose: register an additional peer at runtime — the placeholder
     * server calls this when a WANT_FLOW arrives, mapping the requester's
     * synthetic node id to its UDP data address. Re-registering the same id
     * replaces the old address.
     *
     * @param nodeId  the destination node id to send datagrams to
     * @param address the peer's UDP socket address
     * @throws IllegalStateException if the channel is closed
     */
    public void addPeer(int nodeId, InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        if (closed) {
            throw new IllegalStateException("channel closed");
        }
        peersByNodeId.put(nodeId, address);
        nodeIdBySourceKey.put(sourceKey(address), nodeId);
    }

    /**
     * Purpose: the one-peer convenience constructor used by the CLI server
     * mode, where the single client has a synthetic node id.
     *
     * @param listenPort the local UDP port to bind, 0 for ephemeral
     * @param peerNodeId the synthetic node id of the one client
     * @param peer       the client's socket address
     * @return the channel
     * @throws SocketException if the port cannot be bound
     */
    public static UdpDataChannel singlePeer(int listenPort, int peerNodeId, InetSocketAddress peer)
            throws SocketException {
        return new UdpDataChannel(listenPort, Map.of(peerNodeId, peer));
    }

    /**
     * Purpose: the local port this channel is bound to — the actual ephemeral
     * port when 0 was requested at construction.
     *
     * @return the local UDP port
     */
    public int localPort() {
        return socket.getLocalPort();
    }

    /** {@inheritDoc} */
    @Override
    public void sendDatagram(int destinationNodeId, byte[] packet) {
        Objects.requireNonNull(packet, "packet");
        requireStarted();
        InetSocketAddress peer = peersByNodeId.get(destinationNodeId);
        if (peer == null) {
            throw new IllegalArgumentException("unknown destination node id: " + destinationNodeId);
        }
        try {
            socket.send(new DatagramPacket(packet, packet.length, peer));
        } catch (IOException sendFailure) {
            throw new IllegalStateException(
                    "UDP send to " + peer + " failed: " + sendFailure.getMessage(), sendFailure);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>May be called at any time before {@link #close()}; datagrams arriving
     * while no listener is set are dropped, as on a network where nobody
     * listens.</p>
     */
    @Override
    public void setListener(DataChannelListener listener) {
        this.listener = listener;
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        if (closed) {
            throw new IllegalStateException("channel closed");
        }
        if (started) {
            throw new IllegalStateException("channel already started");
        }
        started = true;
        Thread.ofVirtual()
                .name("udp-data-channel-" + socket.getLocalPort())
                .start(this::receiveLoop);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Closing the socket wakes the receiver thread with a
     * {@link SocketException}, which ends the loop.</p>
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        socket.close();
    }

    /**
     * Purpose: receive datagrams until {@link #close()} and hand each one, as
     * a freshly copied array, to the current listener.
     */
    private void receiveLoop() {
        byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
        while (!closed) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(datagram);
            } catch (SocketException closedSocket) {
                return;
            } catch (IOException receiveFailure) {
                if (!closed) {
                    System.out.println("udp channel: receive failed: " + receiveFailure.getMessage());
                }
                return;
            }

            byte[] payload = new byte[datagram.getLength()];
            System.arraycopy(datagram.getData(), datagram.getOffset(), payload, 0, datagram.getLength());

            DataChannelListener current = listener;
            if (current != null) {
                current.onDatagram(resolveNodeId(datagram.getSocketAddress()), payload);
            }
        }
    }

    /**
     * Purpose: map the source address of an inbound datagram back to a peer
     * node id, or {@link #UNKNOWN_NODE_ID} if the source is not a known peer.
     */
    private int resolveNodeId(SocketAddress sourceAddress) {
        Integer nodeId = nodeIdBySourceKey.get(sourceKey((InetSocketAddress) sourceAddress));
        return nodeId != null ? nodeId : UNKNOWN_NODE_ID;
    }

    /** Purpose: the map key for a socket address, "ip:port". */
    private static String sourceKey(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    /** Purpose: enforce the started-and-not-closed lifecycle on sends. */
    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("channel not started");
        }
        if (closed) {
            throw new IllegalStateException("channel closed");
        }
    }
}
