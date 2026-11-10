package dev.pl36.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A reader for the MJPEG test-video format of the course's ProgEx examples
 * ({@code movie.Mjpeg}), extracted from the book code's {@code VideoStream}.
 *
 * <p>Purpose: turn a video file into a sequence of independent JPEG frames.
 * The file is a flat concatenation of frames; each frame is a 5-byte ASCII
 * decimal length followed by that many bytes of one JPEG image.</p>
 *
 * <p>Invariants: frames are returned in file order, exactly once each;
 * {@link #nextFrame()} returns {@code null} only at a clean end of file (on a
 * frame boundary); {@link #frameNumber()} is the number of frames returned so
 * far.</p>
 *
 * <p>Failure modes: a missing or unreadable file throws {@link IOException}
 * from the constructor; a truncated length prefix, a non-numeric or
 * out-of-range length, or a payload cut short by end of file throw
 * {@link IOException} from {@link #nextFrame()}. Not thread-safe; call
 * {@link #close()} when done.</p>
 */
public final class MjpegVideoStream implements AutoCloseable {

    /** Size, in bytes, of the ASCII decimal frame-length prefix. */
    private static final int FRAME_LENGTH_PREFIX_BYTES = 5;

    /** Largest accepted frame payload, guarding against corrupt lengths. */
    private static final int MAX_FRAME_BYTES = 10 * 1024 * 1024;

    private final InputStream input;
    private long frameNumber;

    /**
     * Purpose: open {@code videoFile} for sequential frame reading.
     *
     * @param videoFile path to an MJPEG file in the 5-byte-length format
     * @throws IOException if the file cannot be opened
     */
    public MjpegVideoStream(Path videoFile) throws IOException {
        this.input = Files.newInputStream(videoFile);
    }

    /**
     * Purpose: read the next frame's JPEG payload.
     *
     * @return the payload bytes (possibly empty), or {@code null} at a clean
     *         end of file
     * @throws IOException on a truncated or malformed frame
     */
    public byte[] nextFrame() throws IOException {
        byte[] prefix = new byte[FRAME_LENGTH_PREFIX_BYTES];
        int prefixRead = readUpTo(input, prefix);
        if (prefixRead == 0) {
            return null;
        }
        if (prefixRead < FRAME_LENGTH_PREFIX_BYTES) {
            throw new IOException("truncated frame length prefix: " + prefixRead + " bytes");
        }

        int frameLength = parseFrameLength(new String(prefix, StandardCharsets.US_ASCII));
        byte[] payload = new byte[frameLength];
        int payloadRead = readUpTo(input, payload);
        if (payloadRead < frameLength) {
            throw new IOException(
                    "truncated frame payload: expected " + frameLength + " bytes, got " + payloadRead);
        }

        frameNumber++;
        return payload;
    }

    /**
     * Purpose: the number of frames returned by {@link #nextFrame()} so far.
     *
     * @return the frame count, starting at 0
     */
    public long frameNumber() {
        return frameNumber;
    }

    /**
     * Purpose: close the underlying file. Idempotent.
     *
     * @throws IOException if closing the file fails
     */
    @Override
    public void close() throws IOException {
        input.close();
    }

    /**
     * Purpose: fill as much of {@code buffer} as the stream yields, without
     * blocking past end of file.
     *
     * @return the number of bytes read, 0 at a clean end of file
     */
    private static int readUpTo(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = input.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    /**
     * Purpose: parse the 5-character ASCII decimal frame length.
     *
     * @throws IOException if the prefix is not a number in range
     */
    private static int parseFrameLength(String prefix) throws IOException {
        final int frameLength;
        try {
            frameLength = Integer.parseInt(prefix);
        } catch (NumberFormatException cause) {
            throw new IOException("malformed frame length prefix: '" + prefix + "'", cause);
        }
        if (frameLength < 0 || frameLength > MAX_FRAME_BYTES) {
            throw new IOException(
                    "frame length out of range 0.." + MAX_FRAME_BYTES + ": " + frameLength);
        }
        return frameLength;
    }
}
