package dev.pl36.net;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Converts control messages to and from the framed byte format of the TCP
 * control channel.
 *
 * <p>Purpose: the single place where the control wire format is defined, so
 * that the transport, routing and streaming workstreams all share one codec
 * and cannot silently disagree about the format.</p>
 *
 * <p>Wire format: a 4-byte big-endian length prefix, followed by exactly that
 * many bytes of UTF-8 JSON describing the {@link ControlMessage} components.
 * {@link MessageType} values serialise as their enum names, so adding values
 * to the enum is not a wire change.</p>
 *
 * <p>Invariants: for every message {@code m},
 * {@code decode(encode(m))} equals {@code m}; the JSON body never exceeds
 * {@value #MAX_FRAME_BODY_BYTES} bytes, and decoding rejects frames that
 * declare more. Decoding ignores unknown JSON fields, so the envelope can be
 * extended with new fields without breaking old code. This class is stateless
 * and thread-safe.</p>
 *
 * <p>Failure modes: every malformed input — a truncated frame, a negative or
 * oversized length prefix, invalid JSON, or an unknown message type — is
 * reported as {@link IllegalArgumentException}; the codec never blocks or
 * hangs. Encoding a message whose body exceeds the maximum throws
 * {@link IllegalArgumentException} as well.</p>
 */
public final class WireCodec {

    /** The largest JSON body, in bytes, a frame may carry. */
    public static final int MAX_FRAME_BODY_BYTES = 64 * 1024;

    /** Shared, thread-safe mapper configured for forward compatibility. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private WireCodec() {
    }

    /**
     * Purpose: serialise {@code message} into one complete length-framed
     * chunk of bytes, ready to be written to the TCP control channel.
     *
     * @param message the message to serialise
     * @return the framed bytes: 4-byte big-endian body length, then the UTF-8
     *         JSON body
     * @throws NullPointerException     if {@code message} is null
     * @throws IllegalArgumentException if the JSON body exceeds
     *                                  {@value #MAX_FRAME_BODY_BYTES} bytes
     */
    public static byte[] encode(ControlMessage message) {
        Objects.requireNonNull(message, "message");

        byte[] body;
        try {
            body = JSON.writeValueAsBytes(message);
        } catch (JsonProcessingException cause) {
            throw new IllegalArgumentException("message is not encodable: " + message, cause);
        }
        if (body.length > MAX_FRAME_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "json body exceeds frame maximum of " + MAX_FRAME_BODY_BYTES
                            + " bytes: " + body.length);
        }

        ByteBuffer frame = ByteBuffer.allocate(4 + body.length);
        frame.putInt(body.length);
        frame.put(body);
        return frame.array();
    }

    /**
     * Purpose: deserialise one complete frame back into a message.
     *
     * @param frame the framed bytes exactly as produced by
     *              {@link #encode(ControlMessage)}
     * @return the message the frame was built from
     * @throws NullPointerException     if {@code frame} is null
     * @throws IllegalArgumentException if the frame is truncated, declares a
     *                                  negative or oversized body length, or
     *                                  does not contain valid JSON of a known
     *                                  message type
     */
    public static ControlMessage decode(byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < 4) {
            throw new IllegalArgumentException("truncated frame: " + frame.length + " bytes");
        }

        int bodyLength = ((frame[0] & 0xFF) << 24)
                | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8)
                | (frame[3] & 0xFF);
        if (bodyLength < 0) {
            throw new IllegalArgumentException("negative frame length: " + bodyLength);
        }
        if (bodyLength > MAX_FRAME_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "frame length exceeds maximum of " + MAX_FRAME_BODY_BYTES
                            + " bytes: " + bodyLength);
        }
        if (frame.length != 4 + bodyLength) {
            throw new IllegalArgumentException(
                    "frame length " + frame.length + " does not match declared body length "
                            + bodyLength);
        }

        try {
            return JSON.readValue(frame, 4, bodyLength, ControlMessage.class);
        } catch (IOException cause) {
            throw new IllegalArgumentException("malformed frame body", cause);
        }
    }
}
