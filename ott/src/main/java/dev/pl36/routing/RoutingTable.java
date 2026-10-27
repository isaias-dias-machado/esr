package dev.pl36.routing;

import dev.pl36.scenario.Neighbor;
import dev.pl36.scenario.Node;
import dev.pl36.scenario.Scenario;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A direct-neighbour routing table built from a {@link Scenario}.
 *
 * <p>Purpose: turn the scenario's link list into a lookup that answers "which
 * nodes can this overlay node reach with a single link?", without walking the
 * link list on every call.</p>
 *
 * <p>Invariants: every node declared in the scenario has an entry, even when
 * it has no links; each neighbour id refers to a declared node; links are
 * undirected, so a pair {@code [a, b]} appears both under {@code a} and under
 * {@code b}; the table and its sets are immutable. Lookups are keyed by
 * {@link Node#id()}, not by IP address.</p>
 *
 * <p>Failure modes: {@link #from(Scenario)} throws
 * {@link NullPointerException} for a {@code null} scenario and
 * {@link IllegalArgumentException} if the scenario has a duplicate node id, a
 * link to an undeclared node, or a link from a node to itself.
 * {@link #neighboursOf(int)} throws {@link IllegalArgumentException} for an
 * undeclared node id.</p>
 */
public final class RoutingTable {

    private final Map<Integer, Set<Integer>> neighboursByNodeId;

    private RoutingTable(Map<Integer, Set<Integer>> neighboursByNodeId) {
        this.neighboursByNodeId = neighboursByNodeId;
    }

    /**
     * Purpose: build the direct-neighbour table described by {@code scenario}.
     *
     * @param scenario scenario whose links should become routing entries
     * @return an immutable table keyed by node id
     * @throws NullPointerException     if {@code scenario} is {@code null}
     * @throws IllegalArgumentException if the scenario breaks a node/link rule
     */
    public static RoutingTable from(Scenario scenario) {
        Objects.requireNonNull(scenario, "scenario");

        Map<Integer, Set<Integer>> mutableTable = new HashMap<>();
        for (Node node : scenario.nodes()) {
            if (mutableTable.put(node.id(), new HashSet<>()) != null) {
                throw new IllegalArgumentException("duplicate node id: " + node.id());
            }
        }

        for (Neighbor neighbor : scenario.neighbors()) {
            int firstId = neighbor.first();
            int secondId = neighbor.second();

            if (firstId == secondId) {
                throw new IllegalArgumentException("node linked to itself: " + firstId);
            }

            Set<Integer> firstNeighbours = mutableTable.get(firstId);
            if (firstNeighbours == null) {
                throw new IllegalArgumentException(
                        "link " + neighbor.pair() + " refers to unknown node id " + firstId);
            }

            Set<Integer> secondNeighbours = mutableTable.get(secondId);
            if (secondNeighbours == null) {
                throw new IllegalArgumentException(
                        "link " + neighbor.pair() + " refers to unknown node id " + secondId);
            }

            firstNeighbours.add(secondId);
            secondNeighbours.add(firstId);
        }

        Map<Integer, Set<Integer>> immutableTable = new HashMap<>();
        mutableTable.forEach((nodeId, neighbours) ->
                immutableTable.put(nodeId, Set.copyOf(neighbours)));

        return new RoutingTable(Map.copyOf(immutableTable));
    }

    /**
     * Purpose: expose the whole table for callers that need to iterate over
     * every node, for example to serialise it.
     *
     * @return immutable map from node id to the ids of its direct neighbours
     */
    public Map<Integer, Set<Integer>> asMap() {
        return neighboursByNodeId;
    }

    /**
     * Purpose: get the direct neighbours of one node.
     *
     * @param nodeId id of a node declared in the original scenario
     * @return immutable ids of the nodes linked directly to {@code nodeId}
     * @throws IllegalArgumentException if {@code nodeId} is not in the table
     */
    public Set<Integer> neighboursOf(int nodeId) {
        Set<Integer> neighbours = neighboursByNodeId.get(nodeId);
        if (neighbours == null) {
            throw new IllegalArgumentException("unknown node id: " + nodeId);
        }
        return neighbours;
    }

    /**
     * Purpose: check whether a node exists in the table, which is useful when
     * the id comes from untrusted input.
     */
    public boolean containsNode(int nodeId) {
        return neighboursByNodeId.containsKey(nodeId);
    }
}
