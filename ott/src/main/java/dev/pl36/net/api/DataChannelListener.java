package dev.pl36.net.api;

import dev.pl36.net.DataPacketHeader;

/**
 * Callbacks for inbound stream datagrams.
 *
 * <p>Purpose: let the streaming workstream receive video packets without
 * knowing anything about sockets.</p>
 *
 * <p>Invariants: called only after the channel has started and before it is
 * closed; every datagram arrives exactly once or not at all — UDP never
 * reorders or duplicates within this seam.</p>
 *
 * <p>Failure modes: implementations should return quickly — they run on the
 * transport's delivery path; an exception thrown by a listener propagates to
 * the sending call of the channel, so misbehaving listeners are visible in
 * tests.</p>
 */
public interface DataChannelListener {

    /**
     * Purpose: deliver one raw datagram received from a direct neighbour.
     *
     * @param fromNodeId the scenario node id of the sending neighbour
     * @param packet      the raw datagram bytes, structured according to
     *                    {@link DataPacketHeader}
     */
    void onDatagram(int fromNodeId, byte[] packet);
}
