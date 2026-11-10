package dev.pl36.net;

/**
 * The kinds of control messages exchanged on the TCP control channel.
 *
 * <p>Purpose: identify the meaning of a {@link ControlMessage} envelope so
 * that receivers can react without inspecting the opaque payload.</p>
 *
 * <p>Invariants: the enum <em>names</em> are the wire format — {@link WireCodec}
 * serialises them as plain strings. This is the documented extension point for
 * the routing workstream: new routing message kinds are added here as new
 * values, which is not a wire change, because every value serialises to its
 * own name and the codec only rejects names the receiving enum does not
 * know.</p>
 *
 * <p>Failure modes: an unknown name encountered during decoding surfaces as
 * {@link IllegalArgumentException} from {@link WireCodec#decode(byte[])}, which
 * is how a receiver detects a malformed or too-new message.</p>
 */
public enum MessageType {

    /** Announces that a process has joined the overlay and is ready to be a neighbour. */
    HELLO,

    /** Announces a graceful leave, so neighbours update immediately instead of waiting for a timeout. */
    GOODBYE,

    /** Refreshes an existing neighbour connection so it is not considered dead. */
    KEEPALIVE,

    /**
     * Client-initiated request for a flow (Stage 2, Strategy 2). The
     * {@code flowId} names the content, not a server; {@code messageId}
     * dedups the request when it is flooded; the {@code payload} carries the
     * requester's data endpoint in the localhost placeholder (its UDP listen
     * port — the host comes from the control connection itself).
     */
    WANT_FLOW,

    /**
     * Withdraws a previous {@link #WANT_FLOW} for {@code flowId}: the
     * requester is removed from the flow's destinations. Reserved for
     * graceful mid-stream leaves; connection-death detection is the
     * transport workstream's job.
     */
    LEAVE_FLOW,

    /**
     * Server-side announcement that the sending node owns {@code flowId}
     * (Stage 2, Strategy 1). Strategy-neutral on purpose: which of the two
     * strategies carries these messages is the routing workstream's choice,
     * not the streaming code's.
     */
    ANNOUNCE
}
