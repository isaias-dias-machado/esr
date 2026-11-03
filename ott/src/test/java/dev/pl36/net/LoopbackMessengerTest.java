package dev.pl36.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Purpose: verify the in-JVM fake — delivery, connectivity events,
 * controlled flooding, and predictable link failures, so the routing and
 * streaming workstreams can rely on it as a testbed.
 */
class LoopbackMessengerTest {

    @Test
    void wiredMessengersDeliverMessagesAndFireNeighborUp() {
        LoopbackMessenger one = new LoopbackMessenger(1);
        LoopbackMessenger two = new LoopbackMessenger(2);
        one.wire(two);

        RecordingControlListener oneListener = new RecordingControlListener();
        RecordingControlListener twoListener = new RecordingControlListener();
        one.setListener(oneListener);
        two.setListener(twoListener);
        one.start();
        two.start();

        assertEquals(List.of(2), oneListener.neighboursUp);
        assertEquals(List.of(1), twoListener.neighboursUp);

        ControlMessage hello = new ControlMessage(MessageType.HELLO, 42L, null, "join");
        one.send(2, hello);

        assertEquals(List.of(hello), twoListener.received);
        assertTrue(oneListener.received.isEmpty(), "sender must not receive its own message");
    }

    @Test
    void wireAfterStartFiresNeighborUpOnBothSides() {
        LoopbackMessenger one = new LoopbackMessenger(1);
        LoopbackMessenger two = new LoopbackMessenger(2);
        RecordingControlListener oneListener = new RecordingControlListener();
        RecordingControlListener twoListener = new RecordingControlListener();
        one.setListener(oneListener);
        two.setListener(twoListener);
        one.start();
        two.start();

        one.wire(two);

        assertEquals(List.of(2), oneListener.neighboursUp);
        assertEquals(List.of(1), twoListener.neighboursUp);
    }

    @Test
    void floodingSkipsTheExcludedNeighbourAndBrokenLinks() {
        // Topology: 1 - 2 - 3, node 2 forwards.
        LoopbackMessenger one = new LoopbackMessenger(1);
        LoopbackMessenger two = new LoopbackMessenger(2);
        LoopbackMessenger three = new LoopbackMessenger(3);
        one.wire(two);
        two.wire(three);

        RecordingControlListener oneListener = new RecordingControlListener();
        RecordingControlListener twoListener = new RecordingControlListener();
        RecordingControlListener threeListener = new RecordingControlListener();
        one.setListener(oneListener);
        two.setListener(twoListener);
        three.setListener(threeListener);
        one.start();
        two.start();
        three.start();

        ControlMessage keepalive = new ControlMessage(MessageType.KEEPALIVE, 7L, null, "");
        two.sendToNeighboursExcept(1, keepalive);
        assertEquals(List.of(), oneListener.received, "excluded neighbour must be skipped");
        assertEquals(List.of(keepalive), threeListener.received);

        // An origin uses a non-neighbour exclusion id: everyone gets it.
        ControlMessage hello = new ControlMessage(MessageType.HELLO, 8L, null, "join");
        two.sendToNeighboursExcept(two.nodeId(), hello);
        assertEquals(List.of(hello), oneListener.received);
        assertEquals(List.of(keepalive, hello), threeListener.received);

        // A broken link is silently skipped instead of throwing.
        one.breakLink(2);
        two.sendToNeighboursExcept(two.nodeId(), hello);
        assertEquals(List.of(keepalive, hello, hello), threeListener.received);
    }

    @Test
    void breakingALinkFiresNeighborDownOnBothEndsAndFailsSendsPredictably() {
        LoopbackMessenger one = new LoopbackMessenger(1);
        LoopbackMessenger two = new LoopbackMessenger(2);
        one.wire(two);
        RecordingControlListener oneListener = new RecordingControlListener();
        RecordingControlListener twoListener = new RecordingControlListener();
        one.setListener(oneListener);
        two.setListener(twoListener);
        one.start();
        two.start();

        one.breakLink(2);

        assertEquals(List.of(2), oneListener.neighboursDown);
        assertEquals(List.of(1), twoListener.neighboursDown);

        ControlMessage message = new ControlMessage(MessageType.KEEPALIVE, 1L, null, "");
        assertThrows(IllegalStateException.class, () -> one.send(2, message));
        assertThrows(IllegalStateException.class, () -> two.send(1, message));
        assertThrows(IllegalStateException.class, () -> one.sendDatagram(2, new byte[]{1}));
    }

    @Test
    void closingFiresNeighborDownOnPeersAndFailsSendsPredictably() {
        LoopbackMessenger one = new LoopbackMessenger(1);
        LoopbackMessenger two = new LoopbackMessenger(2);
        one.wire(two);
        RecordingControlListener oneListener = new RecordingControlListener();
        RecordingControlListener twoListener = new RecordingControlListener();
        one.setListener(oneListener);
        two.setListener(twoListener);
        one.start();
        two.start();

        one.close();

        assertEquals(List.of(2), oneListener.neighboursDown);
        assertEquals(List.of(1), twoListener.neighboursDown);

        ControlMessage message = new ControlMessage(MessageType.KEEPALIVE, 1L, null, "");
        assertThrows(IllegalStateException.class, () -> one.send(2, message));
        assertThrows(IllegalStateException.class, () -> two.send(1, message));
    }

    @Test
    void datagramsArriveCopiedSoSenderAndReceiverDoNotShareState() {
        LoopbackMessenger one = new LoopbackMessenger(1);
        LoopbackMessenger two = new LoopbackMessenger(2);
        one.wire(two);

        List<byte[]> received = new ArrayList<>();
        two.setListener((DataChannelListener) (fromNodeId, packet) -> {
            received.add(packet);
            packet[0] = 99; // mutate the delivered copy
        });
        one.start();
        two.start();

        byte[] original = {1, 2, 3};
        one.sendDatagram(2, original);

        assertEquals(1, received.size());
        assertEquals(1, original[0], "the fake must copy datagrams like a real network");
        assertEquals(99, received.get(0)[0]);
    }

    @Test
    void misuseFailsPredictably() {
        LoopbackMessenger one = new LoopbackMessenger(1);
        LoopbackMessenger two = new LoopbackMessenger(2);
        one.wire(two);

        ControlMessage message = new ControlMessage(MessageType.HELLO, 1L, null, "join");

        // Sends before start and to unknown destinations.
        assertThrows(IllegalStateException.class, () -> one.send(2, message));
        one.setListener(new RecordingControlListener());
        two.setListener(new RecordingControlListener());
        one.start();
        two.start();
        assertThrows(IllegalArgumentException.class, () -> one.send(99, message));
        assertThrows(IllegalArgumentException.class, () -> one.breakLink(99));

        // Wiring mistakes.
        assertThrows(IllegalArgumentException.class, () -> one.wire(one));
        assertThrows(IllegalStateException.class, () -> one.wire(two));

        // Two distinct messengers claiming the same node id.
        LoopbackMessenger imposterOne = new LoopbackMessenger(9);
        LoopbackMessenger imposterTwo = new LoopbackMessenger(9);
        assertThrows(IllegalArgumentException.class, () -> imposterOne.wire(imposterTwo));

        // Restarting after close.
        one.close();
        assertThrows(IllegalStateException.class, one::start);
    }

    /**
     * Purpose: record everything a control listener observes, in order, so
     * tests can assert exact event sequences.
     */
    private static final class RecordingControlListener implements ControlMessengerListener {

        final List<ControlMessage> received = new ArrayList<>();
        final List<Integer> neighboursUp = new ArrayList<>();
        final List<Integer> neighboursDown = new ArrayList<>();

        @Override
        public void onMessage(int fromNodeId, ControlMessage message) {
            received.add(message);
        }

        @Override
        public void onNeighborUp(int nodeId) {
            neighboursUp.add(nodeId);
        }

        @Override
        public void onNeighborDown(int nodeId) {
            neighboursDown.add(nodeId);
        }
    }
}
