package dev.pl36.streaming;

import dev.pl36.net.DataPacketHeader;
import dev.pl36.net.LoopbackMessenger;
import dev.pl36.net.api.DataChannelListener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Purpose: verify the extracted streaming pair over the in-JVM loopback —
 * whole-video delivery in order, gap detection, late-packet drop, and
 * tolerance of foreign or malformed datagrams.
 */
class StreamSourceSinkLoopbackTest {

    private static final String FLOW_ID = "movie.Mjpeg";

    @TempDir
    Path tempDir;

    @Test
    void sendsWholeVideoInOrderOverLoopback() throws IOException {
        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNode = new LoopbackMessenger(1);
        serverNode.wire(clientNode);

        RecordingFrameHandler handler = new RecordingFrameHandler();
        StreamSink sink = new StreamSink(FLOW_ID, handler);
        clientNode.setListener((DataChannelListener) sink::onDatagram);
        serverNode.start();
        clientNode.start();

        Path video = writeVideo("frame-1", "frame-2", "frame-3");
        long sent;
        try (MjpegVideoStream videoStream = new MjpegVideoStream(video)) {
            StreamSource source = new StreamSource(FLOW_ID, videoStream, serverNode, List.of(1), 1L);
            sent = source.pumpAll();
        }

        assertEquals(3, sent);
        assertEquals(3, sink.framesReceived());
        assertEquals(0, sink.gapsDetected());
        assertEquals(0, sink.latePacketsDropped());
        assertEquals(List.of(1L, 2L, 3L), handler.sequenceNumbers);
        assertArrayEquals("frame-2".getBytes(StandardCharsets.UTF_8), handler.payloads.get(1));
    }

    @Test
    void detectsGapsAndDropsLatePackets() {
        RecordingFrameHandler handler = new RecordingFrameHandler();
        StreamSink sink = new StreamSink(FLOW_ID, handler);

        sink.onDatagram(0, datagram(FLOW_ID, 1L, "a"));
        sink.onDatagram(0, datagram(FLOW_ID, 3L, "c")); // frame 2 lost
        sink.onDatagram(0, datagram(FLOW_ID, 2L, "b")); // late, dropped
        sink.onDatagram(0, datagram(FLOW_ID, 4L, "d"));

        assertEquals(3, sink.framesReceived(), "frames 1, 3 and 4 are delivered");
        assertEquals(List.of(1L, 3L, 4L), handler.sequenceNumbers);
        assertEquals(1, sink.gapsDetected());
        assertEquals(1, sink.latePacketsDropped());
        assertEquals(List.of("2..2"), handler.gaps);
    }

    @Test
    void ignoresOtherFlowsAndMalformedDatagrams() {
        RecordingFrameHandler handler = new RecordingFrameHandler();
        StreamSink sink = new StreamSink(FLOW_ID, handler);

        sink.onDatagram(0, datagram("other/movie.Mjpeg", 1L, "x"));
        sink.onDatagram(0, new byte[]{1, 2, 3});

        assertEquals(0, sink.framesReceived());
        assertEquals(0, sink.gapsDetected());
        assertEquals(List.of(), handler.sequenceNumbers);
    }

    @Test
    void rejectsEmptyDestinationList() throws IOException {
        Path video = writeVideo("only-frame");
        try (MjpegVideoStream videoStream = new MjpegVideoStream(video)) {
            assertThrows(IllegalArgumentException.class, () -> new StreamSource(
                    FLOW_ID, videoStream, new LoopbackMessenger(0), List.of(), 1L));
        }
    }

    /** Purpose: encode one datagram in the DataPacketHeader wire format. */
    private static byte[] datagram(String flowId, long sequenceNumber, String payload) {
        return DataPacketHeader.encode(new DataPacketHeader(
                flowId, sequenceNumber, payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Purpose: write a synthetic MJPEG video with one frame per payload. */
    private Path writeVideo(String... payloads) throws IOException {
        byte[][] encoded = new byte[payloads.length][];
        for (int i = 0; i < payloads.length; i++) {
            byte[] payload = payloads[i].getBytes(StandardCharsets.UTF_8);
            byte[] lengthPrefix = String.format("%05d", payload.length)
                    .getBytes(StandardCharsets.US_ASCII);
            encoded[i] = new byte[lengthPrefix.length + payload.length];
            System.arraycopy(lengthPrefix, 0, encoded[i], 0, lengthPrefix.length);
            System.arraycopy(payload, 0, encoded[i], lengthPrefix.length, payload.length);
        }
        int total = 0;
        for (byte[] array : encoded) {
            total += array.length;
        }
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] array : encoded) {
            System.arraycopy(array, 0, all, offset, array.length);
            offset += array.length;
        }
        Path file = Files.createTempFile(tempDir, "video", ".Mjpeg");
        Files.write(file, all);
        return file;
    }

    /**
     * Purpose: record delivered frames and reported gaps in order, so tests
     * can assert exact sequences.
     */
    private static final class RecordingFrameHandler implements FrameHandler {

        final List<Long> sequenceNumbers = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
        final List<String> gaps = new ArrayList<>();

        @Override
        public void onFrame(String flowId, long sequenceNumber, byte[] jpegPayload) {
            sequenceNumbers.add(sequenceNumber);
            payloads.add(jpegPayload);
        }

        @Override
        public void onGap(String flowId, long expectedSequenceNumber, long receivedSequenceNumber) {
            gaps.add(expectedSequenceNumber + ".." + (receivedSequenceNumber - 1));
        }
    }
}
