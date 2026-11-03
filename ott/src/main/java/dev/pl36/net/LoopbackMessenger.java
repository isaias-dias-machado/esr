package dev.pl36.net;

import dev.pl36.net.api.ControlMessenger;
import dev.pl36.net.api.ControlMessengerListener;
import dev.pl36.net.api.DataChannel;
import dev.pl36.net.api.DataChannelListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An in-JVM fake of both the TCP control channel and the UDP data channel.
 *
 * <p>Purpose: let the routing and streaming workstreams develop and test
 * against a small simulated topology weeks before real sockets exist, and
 * give the whole group a debugging tool that needs neither sockets nor the
 * CORE emulator. Wire two instances with {@link #wire(LoopbackMessenger)}
 * to create a link, and break the link later with
 * {@link #breakLink(int)} to simulate failure.</p>
 *
 * <p>Invariants: node ids are unique within a wired topology; links are
 * symmetric — both endpoints know the same link object. Delivery is
 * <em>synchronous and in-memory</em>: a send returns only after the
 * destination listener has run, which keeps tests deterministic without any
 * waiting. Control messages are passed by reference (records are immutable);
 * datagrams are copied, as a real network would. Connectivity and message
 * events fire only while started and only to a set listener; events fired
 * with no listener are dropped. Not thread-safe: instances are meant for
 * single-threaded tests and debugging.</p>
 *
 * <p>Failure modes: {@link IllegalArgumentException} for an unknown
 * destination, wiring a messenger to itself, a duplicate node id, or breaking
 * a nonexistent link; {@link IllegalStateException} for sends before
 * {@link #start()}, after {@link #close()}, on a broken link, duplicate
 * wiring, or a {@link #start()} after {@link #close()}. Sends to a peer that
 * has not started, or that has no listener, are silently dropped, mirroring
 * a network where the peer is not listening yet.</p>
 */
public final class LoopbackMessenger implements ControlMessenger, DataChannel {

    private final int nodeId;
    private final Map<Integer, LoopbackLink> links = new HashMap<>();
    private ControlMessengerListener controlListener;
    private DataChannelListener dataListener;
    private LifecycleState lifecycleState = LifecycleState.NEW;

    /**
     * Purpose: create a messenger representing the overlay node
     * {@code nodeId}.
     *
     * @param nodeId the scenario node id this messenger represents
     */
    public LoopbackMessenger(int nodeId) {
        this.nodeId = nodeId;
    }

    /**
     * Purpose: the scenario node id this messenger represents.
     *
     * @return the node id given at construction
     */
    public int nodeId() {
        return nodeId;
    }

    /**
     * Purpose: create a working bidirectional link between this messenger and
     * {@code neighbour}, simulating two overlay nodes connected in the
     * underlay. Fires {@code onNeighborUp} on each side that has already
     * started.
     *
     * @param neighbour the messenger to link with
     * @throws NullPointerException     if {@code neighbour} is null
     * @throws IllegalArgumentException  if {@code neighbour} is this messenger
     *                                  or carries a duplicate node id
     * @throws IllegalStateException    if either messenger is closed or the
     *                                  link already exists
     */
    public void wire(LoopbackMessenger neighbour) {
        Objects.requireNonNull(neighbour, "neighbour");
        if (neighbour == this) {
            throw new IllegalArgumentException("cannot wire a messenger to itself: " + nodeId);
        }
        if (neighbour.nodeId == nodeId) {
            throw new IllegalArgumentException("duplicate node id on this link: " + nodeId);
        }
        if (lifecycleState == LifecycleState.CLOSED || neighbour.lifecycleState == LifecycleState.CLOSED) {
            throw new IllegalStateException("cannot wire a closed messenger");
        }
        if (links.containsKey(neighbour.nodeId)) {
            throw new IllegalStateException(
                    "link already exists: " + nodeId + " -> " + neighbour.nodeId);
        }

        LoopbackLink link = new LoopbackLink(this, neighbour);
        links.put(neighbour.nodeId, link);
        neighbour.links.put(nodeId, link);

        if (lifecycleState == LifecycleState.STARTED) {
            fireNeighborUp(neighbour.nodeId);
        }
        if (neighbour.lifecycleState == LifecycleState.STARTED) {
            neighbour.fireNeighborUp(nodeId);
        }
    }

    /**
     * Purpose: break the link to {@code neighbourId} at runtime, simulating a
     * link or node failure. Fires {@code onNeighborDown} on both endpoints,
     * whichever side calls it. Idempotent.
     *
     * @param neighbourId the node id of the neighbour to disconnect
     * @throws IllegalArgumentException if no link to {@code neighbourId}
     *                                  exists
     */
    public void breakLink(int neighbourId) {
        LoopbackLink link = links.get(neighbourId);
        if (link == null) {
            throw new IllegalArgumentException("no link from " + nodeId + " to " + neighbourId);
        }
        if (link.broken) {
            return;
        }
        link.broken = true;
        LoopbackMessenger peer = link.otherEndpoint(this);

        fireNeighborDown(neighbourId);
        peer.fireNeighborDown(nodeId);
    }

    /** {@inheritDoc} */
    @Override
    public void send(int destinationNodeId, ControlMessage message) {
        Objects.requireNonNull(message, "message");
        LoopbackLink link = requireUsableLink(destinationNodeId);
        link.otherEndpoint(this).receiveControl(nodeId, message);
    }

    /** {@inheritDoc} */
    @Override
    public void sendToNeighboursExcept(int excludedNodeId, ControlMessage message) {
        Objects.requireNonNull(message, "message");
        requireStarted();
        for (Map.Entry<Integer, LoopbackLink> entry : List.copyOf(links.entrySet())) {
            if (entry.getKey() == excludedNodeId || entry.getValue().broken) {
                continue;
            }
            entry.getValue().otherEndpoint(this).receiveControl(nodeId, message);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void sendDatagram(int destinationNodeId, byte[] packet) {
        Objects.requireNonNull(packet, "packet");
        LoopbackLink link = requireUsableLink(destinationNodeId);
        link.otherEndpoint(this).receiveDatagram(nodeId, packet.clone());
    }

    /**
     * {@inheritDoc}
     *
     * <p>May be called at any time before {@link #close()}; events fired
     * while no listener is set are dropped.</p>
     */
    @Override
    public void setListener(ControlMessengerListener listener) {
        this.controlListener = listener;
    }

    /**
     * {@inheritDoc}
     *
     * <p>May be called at any time before {@link #close()}; datagrams
     * arriving while no listener is set are dropped.</p>
     */
    @Override
    public void setListener(DataChannelListener listener) {
        this.dataListener = listener;
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        if (lifecycleState == LifecycleState.STARTED) {
            throw new IllegalStateException("messenger already started: " + nodeId);
        }
        if (lifecycleState == LifecycleState.CLOSED) {
            throw new IllegalStateException("messenger closed: " + nodeId);
        }
        lifecycleState = LifecycleState.STARTED;
        for (Map.Entry<Integer, LoopbackLink> entry : List.copyOf(links.entrySet())) {
            if (!entry.getValue().broken) {
                fireNeighborUp(entry.getKey());
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Breaks every link this messenger still has, so peers see
     * {@code onNeighborDown} for this node.</p>
     */
    @Override
    public void close() {
        if (lifecycleState == LifecycleState.CLOSED) {
            return;
        }
        for (Map.Entry<Integer, LoopbackLink> entry : List.copyOf(links.entrySet())) {
            if (!entry.getValue().broken) {
                breakLink(entry.getKey());
            }
        }
        lifecycleState = LifecycleState.CLOSED;
    }

    /**
     * Purpose: verify this messenger has started and return a working link.
     */
    private LoopbackLink requireUsableLink(int destinationNodeId) {
        requireStarted();
        if (lifecycleState == LifecycleState.CLOSED) {
            throw new IllegalStateException("messenger closed: " + nodeId);
        }
        LoopbackLink link = links.get(destinationNodeId);
        if (link == null) {
            throw new IllegalArgumentException(
                    "no link from " + nodeId + " to " + destinationNodeId);
        }
        if (link.broken) {
            throw new IllegalStateException(
                    "link from " + nodeId + " to " + destinationNodeId + " is broken");
        }
        return link;
    }

    /**
     * Purpose: verify this messenger has started.
     */
    private void requireStarted() {
        if (lifecycleState == LifecycleState.NEW) {
            throw new IllegalStateException("messenger not started: " + nodeId);
        }
        if (lifecycleState == LifecycleState.CLOSED) {
            throw new IllegalStateException("messenger closed: " + nodeId);
        }
    }

    /**
     * Purpose: deliver one control message to this messenger's listener.
     */
    private void receiveControl(int fromNodeId, ControlMessage message) {
        if (lifecycleState == LifecycleState.STARTED && controlListener != null) {
            controlListener.onMessage(fromNodeId, message);
        }
    }

    /**
     * Purpose: deliver one datagram to this messenger's listener.
     */
    private void receiveDatagram(int fromNodeId, byte[] packet) {
        if (lifecycleState == LifecycleState.STARTED && dataListener != null) {
            dataListener.onDatagram(fromNodeId, packet);
        }
    }

    /**
     * Purpose: fire {@code onNeighborUp} if this messenger is started and has
     * a control listener.
     */
    private void fireNeighborUp(int neighbourId) {
        if (lifecycleState == LifecycleState.STARTED && controlListener != null) {
            controlListener.onNeighborUp(neighbourId);
        }
    }

    /**
     * Purpose: fire {@code onNeighborDown} if this messenger is started and
     * has a control listener.
     */
    private void fireNeighborDown(int neighbourId) {
        if (lifecycleState == LifecycleState.STARTED && controlListener != null) {
            controlListener.onNeighborDown(neighbourId);
        }
    }

    /** The lifecycle states of a messenger. */
    private enum LifecycleState {
        NEW, STARTED, CLOSED
    }

    /**
     * The link between two messengers, shared by both endpoints so that
     * breaking it from either side is visible to both.
     */
    private static final class LoopbackLink {

        private final LoopbackMessenger firstEndpoint;
        private final LoopbackMessenger secondEndpoint;
        private boolean broken;

        private LoopbackLink(LoopbackMessenger firstEndpoint, LoopbackMessenger secondEndpoint) {
            this.firstEndpoint = firstEndpoint;
            this.secondEndpoint = secondEndpoint;
        }

        /**
         * Purpose: the endpoint of this link that is not {@code from}.
         */
        private LoopbackMessenger otherEndpoint(LoopbackMessenger from) {
            return from == firstEndpoint ? secondEndpoint : firstEndpoint;
        }
    }
}
