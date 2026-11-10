package dev.pl36.streaming;

/**
 * The consumer of decoded video frames on the receiving side.
 *
 * <p>Purpose: decouple {@link StreamSink} (datagram reassembly and loss
 * detection) from what the application does with frames — console logging in
 * the demo and CLI client, a playback window later, statistics in tests.</p>
 *
 * <p>Invariants: callbacks fire in sequence-number order per flow, at most
 * once per sequence number; {@code onGap} fires before the {@code onFrame}
 * that follows the gap.</p>
 *
 * <p>Failure modes: implementations must return quickly and must not throw;
 * the sink calls them on the transport's delivery path.</p>
 */
public interface FrameHandler {

    /**
     * Purpose: deliver one received frame.
     *
     * @param flowId         the flow the frame belongs to
     * @param sequenceNumber the frame's sequence number within the flow
     * @param jpegPayload    the raw JPEG bytes of the frame
     */
    void onFrame(String flowId, long sequenceNumber, byte[] jpegPayload);

    /**
     * Purpose: report a sequence gap — frames {@code expectedSequenceNumber}
     * through {@code receivedSequenceNumber - 1} were lost or arrived too
     * late to be played.
     *
     * @param flowId                 the flow the gap belongs to
     * @param expectedSequenceNumber the first sequence number that was expected
     * @param receivedSequenceNumber the sequence number that arrived instead
     */
    void onGap(String flowId, long expectedSequenceNumber, long receivedSequenceNumber);
}
