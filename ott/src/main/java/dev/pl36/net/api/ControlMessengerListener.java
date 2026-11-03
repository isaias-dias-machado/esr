package dev.pl36.net.api;

import dev.pl36.net.ControlMessage;

/**
 * Callbacks for inbound control traffic and connectivity changes.
 *
 * <p>Purpose: let the routing workstream react to messages and to neighbours
 * appearing or disappearing, without knowing anything about sockets.</p>
 *
 * <p>Invariants: methods are called only after the messenger has started and
 * before it is closed; {@code onNeighborUp} fires exactly once per
 * established connection and {@code onNeighborDown} exactly once per
 * connection lost, so up/down events always alternate per node.</p>
 *
 * <p>Failure modes: implementations should return quickly — they run on the
 * transport's delivery path; an exception thrown by a listener propagates to
 * the sending or event-firing call of the messenger, so misbehaving listeners
 * are visible in tests.</p>
 */
public interface ControlMessengerListener {

    /**
     * Purpose: deliver one control message received from a direct neighbour.
     *
     * @param fromNodeId the scenario node id of the sending neighbour
     * @param message    the received message
     */
    void onMessage(int fromNodeId, ControlMessage message);

    /**
     * Purpose: notify that the connection to a neighbour is now usable.
     *
     * @param nodeId the scenario node id of the neighbour that came up
     */
    void onNeighborUp(int nodeId);

    /**
     * Purpose: notify that the connection to a neighbour was lost, by link
     * failure or by {@link ControlMessenger#close()} on either side.
     *
     * @param nodeId the scenario node id of the neighbour that went down
     */
    void onNeighborDown(int nodeId);
}
