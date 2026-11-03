package dev.pl36.net.api;

import dev.pl36.net.ControlMessage;

/**
 * The seam between the routing logic and the TCP transport of the control
 * channel.
 *
 * <p>Purpose: implemented by the transport workstream as a real TCP
 * messenger, and faked in-JVM by {@code LoopbackMessenger}; consumed by the
 * routing workstream for neighbour maintenance and controlled flooding. A
 * messenger deals only with the <em>direct neighbours</em> of the local node
 * — multi-hop routing decisions are the caller's business.</p>
 *
 * <p>Invariants: node ids are the scenario's node ids; the implementation
 * maps them to addresses internally; exactly one listener receives every
 * inbound message and connectivity event; {@link #start()} precedes every
 * send and every event, and {@link #close()} ends them.</p>
 *
 * <p>Failure modes: implementations throw {@link IllegalStateException} if
 * used before {@code start()} or after {@code close()}, or if the link to a
 * destination is down, and {@link IllegalArgumentException} if the
 * destination is not a direct neighbour. These are unchecked on purpose so
 * the signatures stay free of transport exceptions.</p>
 */
public interface ControlMessenger {

    /**
     * Purpose: hand {@code message} to the direct neighbour
     * {@code destinationNodeId} for delivery on the TCP control channel.
     *
     * @param destinationNodeId the scenario node id of the receiving neighbour
     * @param message           the message to deliver
     * @throws NullPointerException     if {@code message} is null
     * @throws IllegalArgumentException if {@code destinationNodeId} is not a
     *                                  direct neighbour
     * @throws IllegalStateException    if this messenger has not started, is
     *                                  closed, or the link to the destination
     *                                  is down
     */
    void send(int destinationNodeId, ControlMessage message);

    /**
     * Purpose: the controlled-flooding primitive — deliver {@code message} to
     * every currently connected neighbour except {@code excludedNodeId},
     * silently skipping broken links. A caller that originates a message
     * passes its own node id (or any non-neighbour id) so that all neighbours
     * receive it; a forwarder passes the id the message arrived from.
     *
     * @param excludedNodeId the node id to skip, typically the hop the
     *                       message came from
     * @param message        the message to deliver
     * @throws NullPointerException  if {@code message} is null
     * @throws IllegalStateException if this messenger has not started or is
     *                               closed
     */
    void sendToNeighboursExcept(int excludedNodeId, ControlMessage message);

    /**
     * Purpose: register the receiver of inbound messages and connectivity
     * events. A null listener drops events. Set it before {@link #start()}
     * to observe every event.
     *
     * @param listener the receiver of inbound control traffic, or null
     */
    void setListener(ControlMessengerListener listener);

    /**
     * Purpose: begin accepting sends and firing listener events. Connectivity
     * events are fired for every neighbour connected at this moment.
     *
     * @throws IllegalStateException if already started or closed
     */
    void start();

    /**
     * Purpose: cease delivery. Every connection is treated as dead, which
     * fires {@link ControlMessengerListener#onNeighborDown(int)} on the
     * peers. Idempotent.
     */
    void close();
}
