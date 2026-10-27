package dev.pl36.scenario;

import java.util.List;

/**
 * The whole content of a scenario YAML file.
 *
 * <p>Invariants: {@code nodes} is never {@code null} and never contains
 * {@code null} entries; the list is immutable.</p>
 *
 * <p>Failure modes: instantiation with a {@code null} list or with {@code null}
 * elements throws {@link NullPointerException} (this is what
 * {@link List#copyOf(java.util.Collection)} guarantees).</p>
 */
public record Scenario(List<Node> nodes) {

    public Scenario {
        nodes = List.copyOf(nodes);
    }
}
