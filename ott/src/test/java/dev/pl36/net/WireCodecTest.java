package dev.pl36.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Purpose: verify the control wire format — roundtrip fidelity, rejection of
 * malformed frames, and the length framing.
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
    void decodeRejectsGarbageBody() {
        // Bytes that are not a valid Kryo stream for any registered type.
        byte[] garbage = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

        assertThrows(IllegalArgumentException.class, () -> WireCodec.decode(frameOf(garbage)));
    }

    @Test
    void encodedFrameStartsWithBigEndianBodyLength() {
        byte[] frame = WireCodec.encode(new ControlMessage(MessageType.HELLO, 1L, null, "join"));

        int declaredLength = bigEndianInt(frame, 0);

        assertEquals(frame.length - 4, declaredLength);
    }

    /**
     * Purpose: wrap {@code body} in a well-formed length frame, so the tests
     * control the raw body bytes exactly.
     */
    private static byte[] frameOf(byte[] body) {
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
