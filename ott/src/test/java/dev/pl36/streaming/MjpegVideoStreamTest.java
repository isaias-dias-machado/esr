package dev.pl36.streaming;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Purpose: verify the MJPEG frame reader — in-order frames, clean end of
 * file, and rejection of malformed files.
 */
class MjpegVideoStreamTest {

    @TempDir
    Path tempDir;

    @Test
    void readsAllFramesInOrderThenReportsEndOfFile() throws IOException {
        Path video = writeVideo("hello", "abc", "");

        try (MjpegVideoStream stream = new MjpegVideoStream(video)) {
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), stream.nextFrame());
            assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), stream.nextFrame());
            assertEquals(0, stream.nextFrame().length, "empty frame must decode to an empty payload");
            assertNull(stream.nextFrame(), "clean end of file must return null");
            assertNull(stream.nextFrame(), "end of file must stay null");
            assertEquals(3, stream.frameNumber());
        }
    }

    @Test
    void rejectsMalformedFiles() throws IOException {
        assertThrows(IOException.class,
                () -> new MjpegVideoStream(tempDir.resolve("missing.Mjpeg")).close());

        try (MjpegVideoStream stream = new MjpegVideoStream(writeRaw("123".getBytes(StandardCharsets.US_ASCII)))) {
            assertThrows(IOException.class, stream::nextFrame, "truncated length prefix");
        }
        try (MjpegVideoStream stream = new MjpegVideoStream(writeRaw("abcde".getBytes(StandardCharsets.US_ASCII)))) {
            assertThrows(IOException.class, stream::nextFrame, "non-numeric length prefix");
        }
        try (MjpegVideoStream stream = new MjpegVideoStream(
                writeRaw("00010abc".getBytes(StandardCharsets.US_ASCII)))) {
            assertThrows(IOException.class, stream::nextFrame, "payload cut short by end of file");
        }
    }

    /**
     * Purpose: write a synthetic MJPEG file — each frame is its 5-digit ASCII
     * decimal length followed by its payload bytes.
     */
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
        return writeRaw(concat(encoded));
    }

    /** Purpose: write raw bytes as a file in the temp dir and return its path. */
    private Path writeRaw(byte[] content) throws IOException {
        Path file = Files.createTempFile(tempDir, "video", ".Mjpeg");
        Files.write(file, content);
        return file;
    }

    /** Purpose: concatenate byte arrays into one. */
    private static byte[] concat(byte[][] arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] all = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, all, offset, array.length);
            offset += array.length;
        }
        return all;
    }
}
