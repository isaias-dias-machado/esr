package dev.pl36.net;

/**
 * The seam between the streaming logic and the UDP transport of the stream
 * data.
 *
 * <p>Purpose: implemented by the transport workstream as a real UDP channel,
 * and faked in-JVM by {@link LoopbackMessenger}; consumed by the streaming
 * workstream to push video packets. The payload layout is pinned by
 * {@link DataPacketHeader}. A channel deals only with the <em>direct
 * neighbours</em> of the local node — forwarding is the routing layer's
 * business.</p>
 *
 * <p>Invariants: node ids are the scenario's node ids; the implementation
 * maps them to addresses internally; exactly one listener receives every
 * inbound datagram; {@link #start()} precedes every send and every event,
 * and {@link #close()} ends them.</p>
 *
 * <p>Failure modes: implementations throw {@link IllegalStateException} if
 * used before {@code start()} or after {@code close()}, or if the link to a
 * destination is down, and {@link IllegalArgumentException} if the
 * destination is not a direct neighbour. The channel never throws for packet
 * loss on the happy path — that is UDP's normal mode of operation.</p>
 */
public interface DataChannel {

    /**
     * Purpose: send one raw datagram to the direct neighbour
     * {@code destinationNodeId} over UDP.
     *
     * @param destinationNodeId the scenario node id of the receiving neighbour
     * @param packet            the raw datagram bytes, structured by the
     *                          caller according to {@link DataPacketHeader}
     * @throws NullPointerException     if {@code packet} is null
     * @throws IllegalArgumentException if {@code destinationNodeId} is not a
     *                                  direct neighbour
     * @throws IllegalStateException    if this channel has not started, is
     *                                  closed, or the link to the destination
     *                                  is down
     */
    void sendDatagram(int destinationNodeId, byte[] packet);

    /**
     * Purpose: register the receiver of inbound datagrams. A null listener
     * drops datagrams. Set it before {@link #start()} to observe every
     * datagram.
     *
     * @param listener the receiver of inbound datagrams, or null
     */
    void setListener(DataChannelListener listener);

    /**
     * Purpose: begin accepting sends and firing listener events.
     *
     * @throws IllegalStateException if already started or closed
     */
    void start();

    /**
     * Purpose: cease delivery, treating every connection as dead. Idempotent.
     */
    void close();
}
