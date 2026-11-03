package dev.pl36.net;

import java.util.Objects;

/**
 * The envelope exchanged between overlay nodes on the TCP control channel.
 *
 * <p>Purpose: carry one control protocol datum (join, leave, liveness, and
 * later routing information) between two processes. All access goes through
 * {@link WireCodec}, so changing this record is a one-file change for the
 * whole group.</p>
 *
 * <p>Invariants: {@code type} and {@code payload} are non-null;
 * {@code flowId} is null or non-empty and identifies the stream a message
 * concerns (e.g. {@code "server1/movie.mjpg"}); {@code messageId} is unique
 * per source process — the sender guarantees that, not this record — and is
 * reserved for flooding deduplication in the routing workstream.</p>
 *
 * <p>Failure modes: the constructor throws {@link NullPointerException} for a
 * null {@code type} or {@code payload} and {@link IllegalArgumentException}
 * for an empty {@code flowId}.</p>
 */
public record ControlMessage(
        MessageType type,
        long messageId,
        String flowId,
        String payload) {

    public ControlMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (flowId != null && flowId.isEmpty()) {
            throw new IllegalArgumentException("flowId must be null or non-empty");
        }
    }
}
