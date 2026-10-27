package dev.pl36.scenario;

import java.util.List;

/**
 * The whole content of a scenario YAML file, e.g.
 *
 * <pre>
 * port: 5000
 * nodes:
 *   - id: 0
 *     ip_addr: 10.1.15.16/24
 *     description: "n16"
 * neighbors:
 *   - pair: [0, 1]
 * </pre>
 *
 * <p>Invariants: {@code port} is in {@code 0..65535}; {@code nodes} and
 * {@code neighbors} are never {@code null} and never contain {@code null}
 * entries; both lists are immutable. Node ids are unique, every neighbour
 * endpoint refers to a declared node, and a link is listed at most once (in
 * either direction).</p>
 *
 * <p>Failure modes: instantiation with a {@code null} list or with {@code null}
 * elements throws {@link NullPointerException} (this is what
 * {@link List#copyOf(java.util.Collection)} guarantees). The remaining
 * invariants are enforced by {@link ScenarioParser} while reading the file.</p>
 */
public record Scenario(int port, List<Node> nodes, List<Neighbor> neighbors) {

    public Scenario {
        nodes = List.copyOf(nodes);
        neighbors = List.copyOf(neighbors);
    }
}
