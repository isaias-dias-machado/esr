package dev.pl36.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The shared binary layout of every UDP datagram carrying stream data.
 *
 * <p>Purpose: pin down the contract between the streaming workstream (which
 * numbers frames per flow) and the routing workstream (which forwards by
 * flow id), so the two cannot silently disagree about the wire format.</p>
 *
 * <p>Wire format: a 2-byte big-endian length {@code n}, then {@code n} bytes
 * of UTF-8 flow id, then an 8-byte big-endian sequence number, then the
 * payload bytes up to the end of the datagram.</p>
 *
 * <p>Invariants: the flow id encodes to at most 65535 UTF-8 bytes; the
 * payload may be empty; for every header {@code h},
 * {@code decode(encode(h))} equals {@code h}. The caller keeps the whole
 * datagram within the UDP size limit — this layout does not enforce it.
 * Because the payload is an array, {@link #equals} and {@link #hashCode}
 * compare it by content, unlike a plain record.</p>
 *
 * <p>Failure modes: encoding a flow id longer than 65535 UTF-8 bytes and
 * decoding a datagram too short for its declared flow id throw
 * {@link IllegalArgumentException}; the decoders never block or hang.</p>
 */
public record DataPacketHeader(String flowId, long sequenceNumber, byte[] payload) {

    /** Size, in bytes, of the flow id length prefix in the wire format. */
    private static final int FLOW_ID_LENGTH_BYTES = 2;

    /** Size, in bytes, of the sequence number in the wire format. */
    private static final int SEQUENCE_NUMBER_BYTES = 8;

    /** Largest flow id, in UTF-8 bytes, that the 2-byte prefix can announce. */
    private static final int MAX_FLOW_ID_BYTES = 65535;

    public DataPacketHeader {
        Objects.requireNonNull(flowId, "flowId");
        Objects.requireNonNull(payload, "payload");
        int flowIdByteLength = flowId.getBytes(StandardCharsets.UTF_8).length;
        if (flowIdByteLength > MAX_FLOW_ID_BYTES) {
            throw new IllegalArgumentException(
                    "flow id exceeds " + MAX_FLOW_ID_BYTES + " utf-8 bytes: " + flowIdByteLength);
        }
    }

    /**
     * Purpose: serialise {@code header} into the raw bytes of one UDP
     * datagram.
     *
     * @param header the header to serialise
     * @return the datagram bytes in the wire format documented on this record
     * @throws NullPointerException if {@code header} is null
     */
    public static byte[] encode(DataPacketHeader header) {
        Objects.requireNonNull(header, "header");

        byte[] flowIdBytes = header.flowId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer datagram = ByteBuffer.allocate(
                FLOW_ID_LENGTH_BYTES + flowIdBytes.length + SEQUENCE_NUMBER_BYTES
                        + header.payload.length);
        datagram.putShort((short) flowIdBytes.length);
        datagram.put(flowIdBytes);
        datagram.putLong(header.sequenceNumber);
        datagram.put(header.payload);
        return datagram.array();
    }

    /**
     * Purpose: deserialise one raw datagram back into a header.
     *
     * @param datagram the datagram bytes exactly as produced by
     *                 {@link #encode(DataPacketHeader)}
     * @return the header the datagram was built from
     * @throws NullPointerException     if {@code datagram} is null
     * @throws IllegalArgumentException if the datagram is too short for its
     *                                  declared flow id length
     */
    public static DataPacketHeader decode(byte[] datagram) {
        Objects.requireNonNull(datagram, "datagram");
        if (datagram.length < FLOW_ID_LENGTH_BYTES) {
            throw new IllegalArgumentException("truncated datagram: " + datagram.length + " bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(datagram);
        int flowIdLength = buffer.getShort() & 0xFFFF;
        if (datagram.length < FLOW_ID_LENGTH_BYTES + flowIdLength + SEQUENCE_NUMBER_BYTES) {
            throw new IllegalArgumentException(
                    "truncated datagram: flow id length " + flowIdLength
                            + " does not fit in " + datagram.length + " bytes");
        }

        byte[] flowIdBytes = new byte[flowIdLength];
        buffer.get(flowIdBytes);
        long decodedSequenceNumber = buffer.getLong();
        byte[] decodedPayload = new byte[buffer.remaining()];
        buffer.get(decodedPayload);

        return new DataPacketHeader(
                new String(flowIdBytes, StandardCharsets.UTF_8),
                decodedSequenceNumber,
                decodedPayload);
    }

    /**
     * Purpose: compare headers by flow id, sequence number and payload
     * <em>content</em>, because the record's array component would otherwise
     * compare by identity.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DataPacketHeader that)) {
            return false;
        }
        return sequenceNumber == that.sequenceNumber
                && Objects.equals(flowId, that.flowId)
                && Arrays.equals(payload, that.payload);
    }

    /** Purpose: hash headers consistently with {@link #equals(Object)}. */
    @Override
    public int hashCode() {
        int result = Objects.hash(flowId, sequenceNumber);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    /** Purpose: readable debug output that does not dump the whole payload. */
    @Override
    public String toString() {
        return "DataPacketHeader[flowId=" + flowId
                + ", sequenceNumber=" + sequenceNumber
                + ", payloadLength=" + payload.length + "]";
    }
}
