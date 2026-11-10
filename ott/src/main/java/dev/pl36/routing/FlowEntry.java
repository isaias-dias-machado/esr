package dev.pl36.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One per-flow routing entry, in the assignment's form
 * {@code { Flow, Cost, Origin, Destinations, State }}.
 *
 * <p>Purpose: the unit of the per-flow routing table every overlay node
 * keeps. Today the placeholder dispatcher in {@code dev.pl36} holds these
 * entries; the routing workstream's real {@code FlowTable} will hold the
 * same record, so the streaming and CLI code built against it now does not
 * change when the real table lands.</p>
 *
 * <p>Invariants: {@code flowId} is non-empty; {@code destinations} is an
 * immutable list of node ids with no duplicates; {@code cost} is
 * non-negative; {@code origin} is {@link Origin#LOCAL} exactly on the node
 * that owns the flow — in that case {@code originNodeId} is
 * {@link #NO_NODE} and {@code cost} is 0 — and {@link Origin#REMOTE} with a
 * meaningful {@code originNodeId} everywhere else. An entry is
 * {@link State#ACTIVE} while it has at least one destination.</p>
 *
 * <p>Failure modes: the compact constructor throws
 * {@link NullPointerException} for a null component and
 * {@link IllegalArgumentException} for an empty {@code flowId}, a negative
 * {@code cost}, a duplicate destination, or a {@link Origin#REMOTE} entry
 * with {@code originNodeId == NO_NODE}.</p>
 */
public record FlowEntry(
        String flowId,
        long cost,
        Origin origin,
        int originNodeId,
        List<Integer> destinations,
        State state) {

    /** The {@code originNodeId} used when {@link #origin} is {@link Origin#LOCAL}. */
    public static final int NO_NODE = -1;

    public FlowEntry {
        Objects.requireNonNull(flowId, "flowId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destinations, "destinations");
        Objects.requireNonNull(state, "state");
        if (flowId.isEmpty()) {
            throw new IllegalArgumentException("flowId must be non-empty");
        }
        if (cost < 0) {
            throw new IllegalArgumentException("cost must be non-negative: " + cost);
        }
        if (origin == Origin.REMOTE && originNodeId == NO_NODE) {
            throw new IllegalArgumentException("REMOTE origin needs a node id");
        }
        if (origin == Origin.LOCAL && originNodeId != NO_NODE) {
            throw new IllegalArgumentException("LOCAL origin must use NO_NODE");
        }
        destinations = List.copyOf(destinations);
        if (new HashSet<>(destinations).size() != destinations.size()) {
            throw new IllegalArgumentException("duplicate destination in: " + destinations);
        }
    }

    /**
     * Purpose: the entry of the node that owns {@code flowId} — origin
     * {@link Origin#LOCAL}, cost 0, active once it has destinations.
     *
     * @param flowId    the owned flow
     * @param destinations the current requesters
     * @return the local-origin entry
     */
    public static FlowEntry local(String flowId, List<Integer> destinations) {
        return new FlowEntry(flowId, 0L, Origin.LOCAL, NO_NODE, destinations, State.ACTIVE);
    }

    /**
     * Purpose: add {@code destinationNodeId} to {@code destinations} if
     * absent, keeping the entry active. Returns an equal entry when the
     * destination is already present.
     */
    public FlowEntry withDestinationAdded(int destinationNodeId) {
        if (destinations.contains(destinationNodeId)) {
            return this;
        }
        return new FlowEntry(flowId, cost, origin, originNodeId,
                append(destinations, destinationNodeId), State.ACTIVE);
    }

    /**
     * Purpose: remove {@code destinationNodeId} from {@code destinations};
     * the entry becomes {@link State#INACTIVE} when no destination remains.
     */
    public FlowEntry withDestinationRemoved(int destinationNodeId) {
        if (!destinations.contains(destinationNodeId)) {
            return this;
        }
        List<Integer> remaining = destinations.stream()
                .filter(destination -> destination != destinationNodeId)
                .toList();
        return new FlowEntry(flowId, cost, origin, originNodeId, remaining,
                remaining.isEmpty() ? State.INACTIVE : State.ACTIVE);
    }

    /** Purpose: append one element to an immutable list. */
    private static List<Integer> append(List<Integer> list, int value) {
        ArrayList<Integer> copy = new ArrayList<>(list);
        copy.add(value);
        return List.copyOf(copy);
    }

    /**
     * Purpose: print the entry in the assignment's compact form, e.g.
     * {@code { Flow: movie.Mjpeg, Cost: 0, Origin: LOCAL, Destinations: [1, 2], State: active }}.
     */
    @Override
    public String toString() {
        String originText = origin == Origin.LOCAL ? "LOCAL" : String.valueOf(originNodeId);
        return "{ Flow: " + flowId
                + ", Cost: " + cost
                + ", Origin: " + originText
                + ", Destinations: " + destinations
                + ", State: " + state.name().toLowerCase(Locale.ROOT) + " }";
    }

    /** Whether the flow originates on this node ({@link #LOCAL}) or arrives from a neighbour ({@link #REMOTE}). */
    public enum Origin {
        LOCAL, REMOTE
    }

    /** Lifecycle of the entry: active while it has destinations, inactive otherwise. */
    public enum State {
        ACTIVE, INACTIVE
    }
}
