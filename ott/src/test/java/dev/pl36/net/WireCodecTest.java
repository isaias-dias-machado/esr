package dev.pl36.net;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Purpose: verify the control wire format — roundtrip fidelity, rejection of
 * malformed frames, and forward compatibility of the envelope.
 */
class WireCodecTest {

    @Test
    void roundtripPreservesMessagesIncludingNonAscii() {
        List<ControlMessage> messages = List.of(
                new ControlMessage(MessageType.HELLO, 42L, null, "join"),
                new ControlMessage(MessageType.GOODBYE, Long.MAX_VALUE, "server1/movie.mjpg", ""),
                new ControlMessage(MessageType.KEEPALIVE, Long.MIN_VALUE, null, ""),
                new ControlMessage(MessageType.KEEPALIVE, 7L, "сервер1/filme.mjpg",
                        " Vídeo — 日本語 ✓"));

        for (ControlMessage message : messages) {
            ControlMessage decoded = WireCodec.decode(WireCodec.encode(message));
            assertEquals(message, decoded, "roundtrip changed: " + message);
        }
    }

    @Test
    void decodeAcceptsEnvelopeWithUnknownFields() {
        // Forward compatibility: a newer sender may add fields without
        // breaking older receivers.
        byte[] frame = frameOf("""
                {"type":"KEEPALIVE","messageId":7,"flowId":null,"payload":"alive","cost":3}
                """);

        ControlMessage decoded = WireCodec.decode(frame);

        assertEquals(new ControlMessage(MessageType.KEEPALIVE, 7L, null, "alive"), decoded);
    }

    @Test
    void decodeRejectsTruncatedFrames() {
        ControlMessage message = new ControlMessage(MessageType.HELLO, 1L, null, "join");
        byte[] frame = WireCodec.encode(message);

        byte[] truncated = new byte[frame.length - 1];
        System.arraycopy(frame, 0, truncated, 0, truncated.length);

        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(truncated));
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(new byte[0]));
    }

    @Test
    void decodeRejectsOversizedDeclaredLength() {
        byte[] oversized = {0x00, 0x01, 0x00, 0x01, 0x00};

        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(oversized));
    }

    @Test
    void encodeRejectsBodiesAboveTheMaximum() {
        String hugePayload = "x".repeat(WireCodec.MAX_FRAME_BODY_BYTES);

        assertThrows(IllegalArgumentException.class,
                () -> WireCodec.encode(
                        new ControlMessage(MessageType.KEEPALIVE, 1L, null, hugePayload)));
    }

    @Test
    void decodeRejectsGarbageJsonBody() {
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(frameOf("xyz")));
    }

    @Test
    void decodeRejectsUnknownMessageType() {
        assertThrows(IllegalArgumentException.class,
                () -> WireCodec.decode(frameOf("""
                        {"type":"BOGUS","messageId":7,"flowId":null,"payload":"?"}
                        """)));
    }

    @Test
    void encodedFrameStartsWithBigEndianBodyLength() {
        byte[] frame = WireCodec.encode(new ControlMessage(MessageType.HELLO, 1L, null, "join"));

        int declaredLength = bigEndianInt(frame, 0);

        assertEquals(frame.length - 4, declaredLength);
    }

    /**
     * Purpose: build a well-formed frame whose body is {@code json}, so the
     * tests control the raw body bytes exactly.
     */
    private static byte[] frameOf(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        return frame;
    }

    /**
     * Purpose: read a 4-byte big-endian integer from {@code bytes}.
     */
    private static int bigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
