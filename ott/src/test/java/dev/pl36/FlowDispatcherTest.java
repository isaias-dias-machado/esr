package dev.pl36;

import dev.pl36.net.ControlMessage;
import dev.pl36.net.LoopbackMessenger;
import dev.pl36.net.MessageType;
import dev.pl36.net.api.ControlMessengerListener;
import dev.pl36.net.api.DataChannelListener;
import dev.pl36.routing.FlowEntry;
import dev.pl36.streaming.FrameHandler;
import dev.pl36.streaming.StreamSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Purpose: verify the placeholder flow dispatcher over the in-JVM loopback —
 * WANT_FLOW resolves a LOCAL flow entry and starts a stream, two flows share
 * one data channel with isolation, two subscribers of one flow both get the
 * identical sequence numbers, duplicates are deduplicated by message id, and
 * LEAVE_FLOW tears the destination down again.
 */
class FlowDispatcherTest {

    private static final String FLOW_ID = "movie.Mjpeg";

    @TempDir
    Path tempDir;

    @Test
    void wantFlowResolvesLocalEntryAndStreams() throws Exception {
        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNode = new LoopbackMessenger(1);
        serverNode.wire(clientNode);

        Map<Integer, String> registeredDataPeers = new HashMap<>();
        FlowDispatcher dispatcher = newDispatcher(serverNode, Map.of(FLOW_ID, writeVideo("f1", "f2", "f3")),
                registeredDataPeers);
        serverNode.setListener(dispatcher);

        RecordingControlListener clientControl = new RecordingControlListener();
        clientNode.setListener(clientControl);
        RecordingFrameHandler handler = new RecordingFrameHandler();
        StreamSink sink = new StreamSink(FLOW_ID, handler);
        clientNode.setListener((DataChannelListener) sink::onDatagram);

        clientNode.start(); // start the client first so it observes the ANNOUNCE fired at server start
        serverNode.start();

        // The server announces its owned flows to the newly connected neighbour.
        assertEquals(List.of(FLOW_ID), clientControl.announcedFlows());

        clientNode.send(0, new ControlMessage(MessageType.WANT_FLOW, 1L, FLOW_ID, "25000"));

        assertEquals("25000", registeredDataPeers.get(1), "requester's data endpoint must be registered");

        FlowEntry entry = dispatcher.entries().get(FLOW_ID);
        assertEquals(FlowEntry.Origin.LOCAL, entry.origin());
        assertEquals(0L, entry.cost());
        assertEquals(List.of(1), entry.destinations());
        assertEquals(FlowEntry.State.ACTIVE, entry.state());

        dispatcher.source(FLOW_ID).awaitTermination();
        assertEquals(3, sink.framesReceived());
        assertEquals(List.of(1L, 2L, 3L), handler.sequenceNumbers);
        assertEquals(0, sink.gapsDetected());
    }

    @Test
    void twoFlowsShareOneChannelWithFlowIsolation() throws Exception {
        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNode = new LoopbackMessenger(1);
        serverNode.wire(clientNode);

        Map<String, Path> flowVideos = Map.of(
                "a.Mjpeg", writeVideo("a1", "a2", "a3"),
                "b.Mjpeg", writeVideo("b1", "b2"));
        FlowDispatcher dispatcher = newDispatcher(serverNode, flowVideos, new HashMap<>());
        serverNode.setListener(dispatcher);
        clientNode.setListener(new RecordingControlListener());

        RecordingFrameHandler handlerA = new RecordingFrameHandler();
        RecordingFrameHandler handlerB = new RecordingFrameHandler();
        StreamSink sinkA = new StreamSink("a.Mjpeg", handlerA);
        StreamSink sinkB = new StreamSink("b.Mjpeg", handlerB);
        clientNode.setListener((DataChannelListener) (fromNodeId, packet) -> {
            sinkA.onDatagram(fromNodeId, packet);
            sinkB.onDatagram(fromNodeId, packet);
        });

        serverNode.start();
        clientNode.start();

        clientNode.send(0, new ControlMessage(MessageType.WANT_FLOW, 1L, "a.Mjpeg", "25000"));
        clientNode.send(0, new ControlMessage(MessageType.WANT_FLOW, 2L, "b.Mjpeg", "25000"));

        dispatcher.source("a.Mjpeg").awaitTermination();
        dispatcher.source("b.Mjpeg").awaitTermination();

        assertEquals(3, sinkA.framesReceived());
        assertEquals(List.of(1L, 2L, 3L), handlerA.sequenceNumbers);
        assertEquals(2, sinkB.framesReceived());
        assertEquals(List.of(1L, 2L), handlerB.sequenceNumbers);
        assertEquals(2, dispatcher.entries().size());
    }

    @Test
    void twoSubscribersForOneFlowGetIdenticalSequenceNumbers() throws Exception {
        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNodeA = new LoopbackMessenger(1);
        LoopbackMessenger clientNodeB = new LoopbackMessenger(2);
        serverNode.wire(clientNodeA);
        serverNode.wire(clientNodeB);

        FlowDispatcher dispatcher = newDispatcher(serverNode, Map.of(FLOW_ID, writeVideo("f1", "f2", "f3")),
                new HashMap<>());
        serverNode.setListener(dispatcher);
        clientNodeA.setListener(new RecordingControlListener());
        clientNodeB.setListener(new RecordingControlListener());

        RecordingFrameHandler handlerA = new RecordingFrameHandler();
        RecordingFrameHandler handlerB = new RecordingFrameHandler();
        StreamSink sinkA = new StreamSink(FLOW_ID, handlerA);
        StreamSink sinkB = new StreamSink(FLOW_ID, handlerB);
        clientNodeA.setListener((DataChannelListener) sinkA::onDatagram);
        clientNodeB.setListener((DataChannelListener) sinkB::onDatagram);

        serverNode.start();
        clientNodeA.start();
        clientNodeB.start();

        clientNodeA.send(0, new ControlMessage(MessageType.WANT_FLOW, 1L, FLOW_ID, "25000"));
        clientNodeB.send(0, new ControlMessage(MessageType.WANT_FLOW, 1L, FLOW_ID, "25001"));

        FlowEntry entry = dispatcher.entries().get(FLOW_ID);
        assertEquals(2, entry.destinations().size(), "Destinations is plural: both requesters");
        assertTrue(entry.destinations().containsAll(List.of(1, 2)));

        dispatcher.source(FLOW_ID).awaitTermination();

        assertEquals(List.of(1L, 2L, 3L), handlerA.sequenceNumbers);
        assertEquals(handlerA.sequenceNumbers, handlerB.sequenceNumbers,
                "both subscribers must see the identical sequence numbers");
        assertEquals(3, sinkA.framesReceived());
        assertEquals(3, sinkB.framesReceived());
    }

    @Test
    void duplicateWantFlowIsDeduplicatedByMessageId() throws Exception {
        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNode = new LoopbackMessenger(1);
        serverNode.wire(clientNode);

        FlowDispatcher dispatcher = newDispatcher(serverNode, Map.of(FLOW_ID, writeVideo("f1")), new HashMap<>());
        serverNode.setListener(dispatcher);
        clientNode.setListener(new RecordingControlListener());
        clientNode.setListener((DataChannelListener) new StreamSink(FLOW_ID, new RecordingFrameHandler())::onDatagram);
        serverNode.start();
        clientNode.start();

        ControlMessage want = new ControlMessage(MessageType.WANT_FLOW, 7L, FLOW_ID, "25000");
        clientNode.send(0, want);
        clientNode.send(0, want); // same messageId: flooded duplicate
        clientNode.send(0, new ControlMessage(MessageType.WANT_FLOW, 8L, FLOW_ID, "25000"));

        assertEquals(List.of(1), dispatcher.entries().get(FLOW_ID).destinations(),
                "one requester, one destination, no matter how often the WANT arrives");
        assertEquals(1, dispatcher.entries().size());

        dispatcher.source(FLOW_ID).awaitTermination();
    }

    @Test
    void leaveFlowRemovesDestinationAndStopsSource() throws Exception {
        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNode = new LoopbackMessenger(1);
        serverNode.wire(clientNode);

        FlowDispatcher dispatcher = newDispatcher(serverNode, Map.of(FLOW_ID, writeVideo("f1", "f2")),
                new HashMap<>());
        serverNode.setListener(dispatcher);
        clientNode.setListener(new RecordingControlListener());
        clientNode.setListener((DataChannelListener) new StreamSink(FLOW_ID, new RecordingFrameHandler())::onDatagram);
        serverNode.start();
        clientNode.start();

        clientNode.send(0, new ControlMessage(MessageType.WANT_FLOW, 1L, FLOW_ID, "25000"));
        assertEquals(FlowEntry.State.ACTIVE, dispatcher.entries().get(FLOW_ID).state());

        clientNode.send(0, new ControlMessage(MessageType.LEAVE_FLOW, 2L, FLOW_ID, ""));

        FlowEntry entry = dispatcher.entries().get(FLOW_ID);
        assertEquals(FlowEntry.State.INACTIVE, entry.state());
        assertEquals(List.of(), entry.destinations());
        assertNull(dispatcher.source(FLOW_ID), "source must be stopped when the last destination leaves");
    }

    @Test
    void wantForUnknownFlowIsIgnored() {
        LoopbackMessenger serverNode = new LoopbackMessenger(0);
        LoopbackMessenger clientNode = new LoopbackMessenger(1);
        serverNode.wire(clientNode);

        FlowDispatcher dispatcher = newDispatcher(serverNode, Map.of(FLOW_ID, tempVideo("f1")), new HashMap<>());
        serverNode.setListener(dispatcher);
        clientNode.setListener(new RecordingControlListener());
        serverNode.start();
        clientNode.start();

        clientNode.send(0, new ControlMessage(MessageType.WANT_FLOW, 1L, "not-owned.Mjpeg", "25000"));

        assertTrue(dispatcher.entries().isEmpty());
        assertNull(dispatcher.source("not-owned.Mjpeg"));
    }

    /**
     * Purpose: build a dispatcher wired to {@code serverNode} as both the
     * control messenger and the data channel, with a recording data-peer
     * registrar.
     */
    private FlowDispatcher newDispatcher(LoopbackMessenger serverNode, Map<String, Path> flowVideos,
                                         Map<Integer, String> registeredDataPeers) {
        return new FlowDispatcher(serverNode, serverNode, flowVideos, 1L,
                (nodeId, dataEndpoint) -> registeredDataPeers.put(nodeId, dataEndpoint));
    }

    /** Purpose: write a synthetic MJPEG video with one frame per payload. */
    private Path writeVideo(String... payloads) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Purpose: a one-frame video, see {@link #writeVideo(String...)}. */
    private Path tempVideo(String payload) {
        return writeVideo(payload);
    }

    /**
     * Purpose: record delivered frames in order, so tests can assert exact
     * sequence numbers.
     */
    private static final class RecordingFrameHandler implements FrameHandler {

        final List<Long> sequenceNumbers = new ArrayList<>();

        @Override
        public void onFrame(String flowId, long sequenceNumber, byte[] jpegPayload) {
            sequenceNumbers.add(sequenceNumber);
        }

        @Override
        public void onGap(String flowId, long expectedSequenceNumber, long receivedSequenceNumber) {
        }
    }

    /**
     * Purpose: record the flows announced via ANNOUNCE, so tests can assert
     * the server announces what it owns.
     */
    private static final class RecordingControlListener implements ControlMessengerListener {

        final List<String> announcedFlows = new ArrayList<>();

        @Override
        public void onMessage(int fromNodeId, ControlMessage message) {
            if (message.type() == MessageType.ANNOUNCE) {
                announcedFlows.add(message.flowId());
            }
        }

        @Override
        public void onNeighborUp(int nodeId) {
        }

        @Override
        public void onNeighborDown(int nodeId) {
        }

        List<String> announcedFlows() {
            return announcedFlows;
        }
    }
}
