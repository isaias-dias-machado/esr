package dev.pl36.streaming;

import dev.pl36.net.LoopbackMessenger;
import dev.pl36.net.api.DataChannelListener;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable in-JVM demo of the extracted streaming pair — no sockets needed.
 *
 * <p>Purpose: prove the ProgEx extraction end to end before any real
 * transport exists: wires a server node and a client node with
 * {@link LoopbackMessenger}, pumps a whole video through a
 * {@link StreamSource} into a {@link StreamSink}, and prints the summary.</p>
 *
 * <p>Usage: {@code java dev.pl36.streaming.StreamingLoopbackDemo [video file]}
 * (defaults to {@code resorces/movie.Mjpeg}).</p>
 */
public final class StreamingLoopbackDemo {

    /** The flow id is the bare video file name (content-addressed). */
    private static final String FLOW_ID = "movie.Mjpeg";

    private StreamingLoopbackDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path videoFile = Path.of(args.length > 0 ? args[0] : "resorces/movie.Mjpeg");

        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNode = new LoopbackMessenger(1);
        serverNode.wire(clientNode);

        RecordingFrameHandler handler = new RecordingFrameHandler();
        StreamSink sink = new StreamSink(FLOW_ID, handler);
        clientNode.setListener((DataChannelListener) sink::onDatagram);
        serverNode.start();
        clientNode.start();

        long sent;
        try (MjpegVideoStream video = new MjpegVideoStream(videoFile)) {
            StreamSource source = new StreamSource(FLOW_ID, video, serverNode, List.of(1),
                    StreamSource.DEFAULT_FRAME_PERIOD_MILLIS);
            sent = source.pumpAll();
        }

        System.out.printf("demo: sent=%d received=%d gaps=%d lateDropped=%d%n",
                sent, sink.framesReceived(), sink.gapsDetected(), sink.latePacketsDropped());
        System.out.println("demo: gaps seen: " + handler.gaps);
    }

    /**
     * Purpose: count frames and record gaps quietly instead of printing every
     * frame, so the demo output is a one-line summary.
     */
    private static final class RecordingFrameHandler implements FrameHandler {

        final List<String> gaps = new ArrayList<>();

        @Override
        public void onFrame(String flowId, long sequenceNumber, byte[] jpegPayload) {
        }

        @Override
        public void onGap(String flowId, long expectedSequenceNumber, long receivedSequenceNumber) {
            gaps.add(expectedSequenceNumber + ".." + (receivedSequenceNumber - 1));
        }
    }
}
