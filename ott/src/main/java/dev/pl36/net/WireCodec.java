package dev.pl36.net;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.EnumNameSerializer;

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
 * many bytes of a Kryo binary body describing the {@link ControlMessage}
 * components. {@link MessageType} values serialise by name through
 * {@link EnumNameSerializer}, so reordering the enum or adding values is not a
 * wire change.</p>
 *
 * <p>Invariants: for every message {@code m},
 * {@code decode(encode(m))} equals {@code m}; the Kryo body never exceeds
 * {@value #MAX_FRAME_BODY_BYTES} bytes, and decoding rejects frames that
 * declare more. The codec is thread-safe: Kryo instances are not, so each
 * thread keeps its own in a {@link ThreadLocal}. References are disabled
 * because a message is a flat record with no shared or cyclic objects.</p>
 *
 * <p>Failure modes: every malformed input — a truncated frame, a negative or
 * oversized length prefix, or a body Kryo cannot deserialise — is reported as
 * {@link IllegalArgumentException}; the codec never blocks or hangs. Encoding a
 * message whose body exceeds the maximum throws
 * {@link IllegalArgumentException} as well.</p>
 *
 * <p>Compatibility: unlike a self-describing format, the Kryo body is tied to
 * the shape of {@link ControlMessage}. Adding, removing or reordering fields is
 * a wire change, so every node must run the same build. That is intentional for
 * the course overlay, where all nodes are launched from one JAR.</p>
 */
public final class WireCodec {

    /** The largest body, in bytes, a frame may carry. */
    public static final int MAX_FRAME_BODY_BYTES = 64 * 1024;

    /** Per-thread serialiser; Kryo instances are not thread-safe. */
    private static final ThreadLocal<Kryo> KRYOS = ThreadLocal.withInitial(WireCodec::newKryo);

    private WireCodec() {
    }

    /**
     * Purpose: build a Kryo configured for the control wire format.
     *
     * <p>The message class is registered because registration is required, and
     * enums are pinned to their names so the {@link MessageType} extension
     * point documented there keeps working. References are disabled so a reused
     * per-thread Kryo never leaks object identity between messages.</p>
     *
     * @return a fresh, thread-confined Kryo
     */
    private static Kryo newKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(true);
        kryo.setReferences(false);
        kryo.register(ControlMessage.class);
        kryo.register(MessageType.class, new EnumNameSerializer(MessageType.class));
        return kryo;
    }

    /**
     * Purpose: serialise {@code message} into one complete length-framed
     * chunk of bytes, ready to be written to the TCP control channel.
     *
     * @param message the message to serialise
     * @return the framed bytes: 4-byte big-endian body length, then the Kryo
     *         binary body
     * @throws NullPointerException     if {@code message} is null
     * @throws IllegalArgumentException if the Kryo body exceeds
     *                                  {@value #MAX_FRAME_BODY_BYTES} bytes or
     *                                  cannot be serialised
     */
    public static byte[] encode(ControlMessage message) {
        Objects.requireNonNull(message, "message");

        byte[] body;
        try (Output output = new Output(128)) {
            KRYOS.get().writeObject(output, message);
            body = output.toBytes();
        } catch (KryoException cause) {
            throw new IllegalArgumentException("message is not encodable: " + message, cause);
        }
        if (body.length > MAX_FRAME_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "kryo body exceeds frame maximum of " + MAX_FRAME_BODY_BYTES
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
     *                                  does not contain a valid Kryo body of a
     *                                  {@link ControlMessage}
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

        ControlMessage decoded;
        try (Input input = new Input(frame, 4, bodyLength)) {
            decoded = KRYOS.get().readObject(input, ControlMessage.class);
        } catch (KryoException cause) {
            throw new IllegalArgumentException("malformed frame body", cause);
        }
        if (decoded == null) {
            throw new IllegalArgumentException("frame body decoded to null");
        }
        return decoded;
    }
}
