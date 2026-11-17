package dev.pl36.scenario;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * The role one overlay node plays in its scenario, as declared by the
 * {@code role} key of a node in the scenario YAML:
 *
 * <pre>
 * - id: 0
 *   ip_addr: 10.1.15.16/24
 *   description: "n16"
 *   role: server
 * </pre>
 *
 * <p>Invariants: the YAML spelling is lowercase ({@code server} /
 * {@code client} / {@code relay}); every other casing or wording is rejected,
 * which keeps the scenario files and {@code context/scenarios.md} honest.
 * Omitting the key means {@link #RELAY}; a node that declares no role just
 * forwards (that behaviour is implemented by
 * {@link Node#Node(int, String, String, NodeRole)}, not by this enum).</p>
 *
 * <p>Failure modes: {@link #fromYamlName(String)} throws
 * {@link IllegalArgumentException} for any string that is not one of the
 * three lowercase names; Jackson surfaces that as an {@code IOException}
 * subclass while parsing, so a typo cannot pass silently.</p>
 */
public enum NodeRole {

    /** Owns flows and serves them to requesters. */
    SERVER,

    /** Names and consumes flows. */
    CLIENT,

    /** Forwards flows between the other roles; the default. */
    RELAY;

    /**
     * Purpose: the lowercase YAML spelling of this role, used both by
     * Jackson serialization ({@code @JsonValue}) and by the
     * {@code ott roles} output that {@code scripts/localhost-demo.sh}
     * parses.
     */
    @JsonValue
    public String yamlName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Purpose: strict deserialization of the YAML spelling.
     *
     * @param yamlName the scalar under {@code role}; {@code null} is passed
     *                 through so {@code Node} can apply its RELAY default
     * @throws IllegalArgumentException if {@code yamlName} is not
     *                                  {@code server}, {@code client} or
     *                                  {@code relay}
     */
    @JsonCreator
    public static NodeRole fromYamlName(String yamlName) {
        if (yamlName == null) {
            return null;
        }
        return switch (yamlName) {
            case "server" -> SERVER;
            case "client" -> CLIENT;
            case "relay" -> RELAY;
            default -> throw new IllegalArgumentException("unknown node role: " + yamlName);
        };
    }
}
