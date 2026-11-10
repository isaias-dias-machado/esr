package dev.pl36.streaming;

/**
 * A {@link FrameHandler} that logs to standard output.
 *
 * <p>Purpose: the headless "playback" of the demo and the CLI client — every
 * received frame and every detected gap is printed, which doubles as honest
 * evidence of UDP loss for the report.</p>
 *
 * <p>Invariants: stateless; one line per callback.</p>
 *
 * <p>Failure modes: none — it never throws.</p>
 */
public final class ConsoleFrameHandler implements FrameHandler {

    /** {@inheritDoc} */
    @Override
    public void onFrame(String flowId, long sequenceNumber, byte[] jpegPayload) {
        System.out.printf("frame flow=%s seq=%d bytes=%d%n",
                flowId, sequenceNumber, jpegPayload.length);
    }

    /** {@inheritDoc} */
    @Override
    public void onGap(String flowId, long expectedSequenceNumber, long receivedSequenceNumber) {
        System.out.printf("GAP flow=%s expected=%d received=%d (lost %d frame(s))%n",
                flowId, expectedSequenceNumber, receivedSequenceNumber,
                receivedSequenceNumber - expectedSequenceNumber);
    }
}
