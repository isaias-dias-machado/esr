package dev.pl36.scenario;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One link declared in a scenario YAML file, e.g.
 *
 * <pre>
 * - pair: [0, 1]
 * </pre>
 *
 * <p>Invariants: {@code pair} holds exactly two node ids and the list is
 * immutable. The ids are checked against the declared nodes by
 * {@link ScenarioParser}, not by this record.</p>
 *
 * <p>Failure modes: constructing a neighbour whose {@code pair} is {@code null},
 * contains {@code null}, or does not have exactly two elements throws
 * {@link NullPointerException} or {@link IllegalArgumentException}. While
 * parsing, Jackson wraps those in an {@code IOException} subclass.</p>
 */
public record Neighbor(@JsonProperty("pair") List<Integer> pair) {

    public Neighbor {
        pair = List.copyOf(pair);
        if (pair.size() != 2) {
            throw new IllegalArgumentException("Expected a pair of two node ids, got: " + pair);
        }
    }

    /**
     * Purpose: first endpoint of the link, as written in the YAML.
     */
    public int first() {
        return pair.get(0);
    }

    /**
     * Purpose: second endpoint of the link, as written in the YAML.
     */
    public int second() {
        return pair.get(1);
    }
}
