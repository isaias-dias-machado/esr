package dev.pl36.routing;

import dev.pl36.scenario.Neighbor;
import dev.pl36.scenario.Node;
import dev.pl36.scenario.Scenario;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The routing state of one running overlay process.
 *
 * <p>Purpose: hold the node this process represents and only the nodes directly
 * linked to it. The process does not need, and does not receive, the rest of
 * the scenario topology.</p>
 *
 * <p>Invariants: {@code self} is a node declared in the scenario; every key in
 * {@code neighbours} is the id of a declared node; every value is the node with
 * that id; links are undirected; the neighbour map is immutable.</p>
 *
 * <p>Failure modes: {@link #from(Scenario, int)} throws
 * {@link NullPointerException} for a {@code null} scenario,
 * {@link IllegalArgumentException} if the given node id is not declared or a
 * local link refers to an unknown node, and {@link IllegalArgumentException} if
 * the scenario contains a link from a node to itself.</p>
 */
public record RoutingTable(Node self, Map<Integer, Node> neighbours) {

    public RoutingTable {
        Objects.requireNonNull(self, "self");
        neighbours = Map.copyOf(neighbours);
    }

    /**
     * Purpose: build the local routing table for one process.
     *
     * @param scenario the whole parsed scenario
     * @param selfId   id of the node represented by the running process
     * @return a table containing only {@code selfId} and its direct neighbours
     * @throws NullPointerException     if {@code scenario} is {@code null}
     * @throws IllegalArgumentException if {@code selfId} is unknown or a local
     *                                  link refers to an unknown node
     */
    public static RoutingTable from(Scenario scenario, int selfId) {
        Objects.requireNonNull(scenario, "scenario");

        Node self = requireNode(scenario, selfId);
        Map<Integer, Node> neighbours = new HashMap<>();

        for (Neighbor neighbor : scenario.neighbors()) {
            if (neighbor.first() == neighbor.second()) {
                throw new IllegalArgumentException(
                        "node linked to itself: " + neighbor.first());
            }

            if (neighbor.first() == selfId) {
                int neighbourId = neighbor.second();
                neighbours.put(neighbourId, requireNode(scenario, neighbourId));
            }

            if (neighbor.second() == selfId) {
                int neighbourId = neighbor.first();
                neighbours.put(neighbourId, requireNode(scenario, neighbourId));
            }
        }

        return new RoutingTable(self, neighbours);
    }

    /**
     * Purpose: find the declared node with {@code nodeId}.
     */
    private static Node requireNode(Scenario scenario, int nodeId) {
        for (Node node : scenario.nodes()) {
            if (node.id() == nodeId) {
                return node;
            }
        }
        throw new IllegalArgumentException("unknown node id: " + nodeId);
    }
}
