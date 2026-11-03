package dev.pl36.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Purpose: verify the UDP packet layout — roundtrip fidelity and rejection
 * of malformed datagrams.
 */
class DataPacketHeaderTest {

    @Test
    void roundtripPreservesHeadersIncludingNonAscii() {
        DataPacketHeader header = new DataPacketHeader(
                "сервер1/filme.mjpg", Long.MAX_VALUE, new byte[]{0x00, 0x01, (byte) 0xFF, 0x7F});

        DataPacketHeader decoded = DataPacketHeader.decode(DataPacketHeader.encode(header));

        assertEquals(header, decoded, "roundtrip changed: " + header);
        assertArrayEquals(header.payload(), decoded.payload());
        assertEquals(Long.MAX_VALUE, decoded.sequenceNumber());
    }

    @Test
    void roundtripPreservesEmptyPayload() {
        DataPacketHeader header = new DataPacketHeader("server1/movie.mjpg", 0L, new byte[0]);

        assertEquals(header, DataPacketHeader.decode(DataPacketHeader.encode(header)));
    }

    @Test
    void decodeRejectsTruncatedDatagrams() {
        byte[] datagram = DataPacketHeader.encode(
                new DataPacketHeader("server1/movie.mjpg", 5L, new byte[]{1, 2, 3}));

        // Cut into the sequence-number region, leaving too few header bytes.
        int truncatedLength = 2 + "server1/movie.mjpg".length() + 8 - 1;
        byte[] truncated = new byte[truncatedLength];
        System.arraycopy(datagram, 0, truncated, 0, truncatedLength);

        assertThrows(IllegalArgumentException.class, () -> DataPacketHeader.decode(truncated));
        assertThrows(IllegalArgumentException.class, () -> DataPacketHeader.decode(new byte[1]));
        assertThrows(IllegalArgumentException.class, () -> DataPacketHeader.decode(new byte[0]));

        // Note: cutting only payload bytes is NOT corruption — the payload is
        // simply "the rest of the datagram", so such a datagram decodes to a
        // shorter payload. The minimum header layout above is what decode
        // enforces.
    }

    @Test
    void constructionRejectsOversizedFlowId() {
        String oversizedFlowId = "a".repeat(70_000);

        assertThrows(IllegalArgumentException.class,
                () -> new DataPacketHeader(oversizedFlowId, 1L, new byte[0]));
    }
}
