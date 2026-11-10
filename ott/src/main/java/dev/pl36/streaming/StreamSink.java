package dev.pl36.streaming;

import dev.pl36.net.DataPacketHeader;
import dev.pl36.net.api.DataChannelListener;

import java.util.Objects;

/**
 * The receiving end of one stream, extracted from the book code's
 * {@code Cliente}.
 *
 * <p>Purpose: turn raw datagrams arriving on a {@link DataChannel} back into
 * an ordered frame sequence for a {@link FrameHandler}, tolerating UDP loss:
 * late or duplicate packets are dropped, gaps are reported through
 * {@link FrameHandler#onGap}, and whatever arrives is delivered — play what
 * arrives, log what is missing.</p>
 *
 * <p>Invariants: only datagrams whose flow id equals {@code flowId} are
 * processed; the first accepted packet of the flow sets the expected sequence
 * number to its own plus one; afterwards every packet with a sequence number
 * greater than or equal to the expected one is delivered and the expected
 * number advances past it, while smaller sequence numbers are dropped as
 * late or duplicate. The statistic counters are monotonic. Thread-safe: all
 * mutable state is guarded by this instance's monitor.</p>
 *
 * <p>Failure modes: a datagram that does not decode as a
 * {@link DataPacketHeader} is logged and dropped, never thrown; a null flow
 * id or handler is rejected at construction with
 * {@link NullPointerException}.</p>
 */
public final class StreamSink implements DataChannelListener {

    private final String flowId;
    private final FrameHandler frameHandler;

    private boolean started;
    private long expectedSequenceNumber;
    private long framesReceived;
    private long latePacketsDropped;
    private long gapsDetected;

    /**
     * Purpose: create a sink for one flow.
     *
     * @param flowId       the flow to accept; datagrams of other flows are ignored
     * @param frameHandler the consumer of decoded frames and gap reports
     */
    public StreamSink(String flowId, FrameHandler frameHandler) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Decodes the datagram, filters by flow, applies the sequence rules
     * documented on this class, and fires the handler callbacks.</p>
     */
    @Override
    public synchronized void onDatagram(int fromNodeId, byte[] packet) {
        final DataPacketHeader header;
        try {
            header = DataPacketHeader.decode(packet);
        } catch (IllegalArgumentException malformed) {
            System.out.printf("sink: dropping malformed datagram from node %d: %s%n",
                    fromNodeId, malformed.getMessage());
            return;
        }
        if (!header.flowId().equals(flowId)) {
            return;
        }

        long sequenceNumber = header.sequenceNumber();
        if (!started) {
            started = true;
            expectedSequenceNumber = sequenceNumber + 1;
        } else if (sequenceNumber < expectedSequenceNumber) {
            latePacketsDropped++;
            return;
        } else if (sequenceNumber > expectedSequenceNumber) {
            gapsDetected++;
            frameHandler.onGap(flowId, expectedSequenceNumber, sequenceNumber);
            expectedSequenceNumber = sequenceNumber + 1;
        } else {
            expectedSequenceNumber++;
        }

        framesReceived++;
        frameHandler.onFrame(flowId, sequenceNumber, header.payload());
    }

    /** Purpose: the flow id this sink accepts. */
    public String flowId() {
        return flowId;
    }

    /** Purpose: frames delivered to the handler so far. */
    public synchronized long framesReceived() {
        return framesReceived;
    }

    /** Purpose: gaps reported so far. */
    public synchronized long gapsDetected() {
        return gapsDetected;
    }

    /** Purpose: late or duplicate packets dropped so far. */
    public synchronized long latePacketsDropped() {
        return latePacketsDropped;
    }
}
