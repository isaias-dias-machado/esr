package dev.pl36.scenario;

import dev.pl36.routing.RoutingTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Purpose: verify the scenario schema the whole group relies on:
 * <ul>
 *   <li>the {@code role} key binds its three lowercase spellings, an omitted
 *       or explicitly null {@code role} falls back to {@link NodeRole#RELAY},
 *       and any other spelling is rejected;</li>
 *   <li>the strictness contract survives the optional {@code role}: missing
 *       load-bearing keys are still parse errors (now via
 *       {@code @JsonProperty(required = true)} instead of the blanket
 *       {@code FAIL_ON_MISSING_CREATOR_PROPERTIES});</li>
 *   <li>the checked-in scenario files (CORE and localhost variants) parse,
 *       follow the agreed localhost address and port scheme, and carry the
 *       agreed roles — scenario 3 has exactly the two servers, ids 1 and 6.</li>
 * </ul>
 *
 * <p>The tests read the YAML files from {@code resorces/} relative to the
 * module directory, which is where Maven runs the tests.</p>
 */
class ScenarioParserTest {

    /** A valid three-node scenario: server — relay — client. */
    private static final String VALID_YAML = """
            port: 5000
            nodes:
              - id: 0
                ip_addr: 10.1.15.16/24
                description: "n16"
                role: server
              - id: 1
                ip_addr: 10.0.9.2/30
                description: "n6"
                role: relay
              - id: 2
                ip_addr: 10.1.12.20/24
                description: "n20"
                role: client
            neighbors:
              - pair: [0, 1]
              - pair: [1, 2]
            """;

    // ------------------------------------------------------------------
    // The `role` field: present / omitted / null / invalid
    // ------------------------------------------------------------------

    @Test
    void roleBindsItsThreeSpellings() throws IOException {
        Scenario scenario = parseYaml(VALID_YAML);
        assertEquals(NodeRole.SERVER, scenario.nodes().get(0).role());
        assertEquals(NodeRole.RELAY, scenario.nodes().get(1).role());
        assertEquals(NodeRole.CLIENT, scenario.nodes().get(2).role());
    }

    @Test
    void omittedRoleDefaultsToRelay() throws IOException {
        Scenario scenario = parseYaml("""
                port: 5000
                nodes:
                  - id: 0
                    ip_addr: 10.1.15.16/24
                    description: "n16"
                neighbors: []
                """);
        assertEquals(NodeRole.RELAY, scenario.nodes().get(0).role());
    }

    @Test
    void explicitlyNullRoleDefaultsToRelay() throws IOException {
        Scenario scenario = parseYaml("""
                port: 5000
                nodes:
                  - id: 0
                    ip_addr: 10.1.15.16/24
                    description: "n16"
                    role: null
                neighbors: []
                """);
        assertEquals(NodeRole.RELAY, scenario.nodes().get(0).role());
    }

    @Test
    void unknownRoleIsRejected() {
        ValueInstantiationException thrown = assertThrows(ValueInstantiationException.class,
                () -> parseYaml("""
                        port: 5000
                        nodes:
                          - id: 0
                            ip_addr: 10.1.15.16/24
                            description: "n16"
                            role: peer
                        neighbors: []
                        """));
        assertTrue(thrown.getMessage().contains("unknown node role: peer"),
                () -> "unexpected message: " + thrown.getMessage());
    }

    // ------------------------------------------------------------------
    // Strictness: missing load-bearing keys are still parse errors
    // ------------------------------------------------------------------

    @Test
    void missingNodeKeyIsStillRejected() {
        MismatchedInputException thrown = assertThrows(MismatchedInputException.class,
                () -> parseYaml("""
                        port: 5000
                        nodes:
                          - id: 0
                            ip_addr: 10.1.15.16/24
                        neighbors: []
                        """));
        assertTrue(thrown.getMessage().contains("description"),
                () -> "unexpected message: " + thrown.getMessage());
    }

    @Test
    void missingScenarioKeyIsStillRejected() {
        assertThrows(MismatchedInputException.class, () -> parseYaml("""
                nodes:
                  - id: 0
                    ip_addr: 10.1.15.16/24
                    description: "n16"
                neighbors: []
                """));
    }

    @Test
    void missingPairIsStillRejected() {
        assertThrows(MismatchedInputException.class, () -> parseYaml("""
                port: 5000
                nodes:
                  - id: 0
                    ip_addr: 10.1.15.16/24
                    description: "n16"
                  - id: 1
                    ip_addr: 10.1.12.20/24
                    description: "n20"
                neighbors:
                  - {}
                """));
    }

    // ------------------------------------------------------------------
    // The checked-in scenario files (context/scenarios.md is the mapping)
    // ------------------------------------------------------------------

    /** Scenario 1: one server (id 0), one relay (id 1), two clients (ids 2, 3). */
    private static final Map<Integer, NodeRole> SCENARIO_1_ROLES =
            Map.of(0, NodeRole.SERVER, 1, NodeRole.RELAY, 2, NodeRole.CLIENT, 3, NodeRole.CLIENT);

    /** Scenario 2: one server (id 0), three relays (ids 1-3), two clients (ids 4, 5). */
    private static final Map<Integer, NodeRole> SCENARIO_2_ROLES =
            Map.ofEntries(
                    Map.entry(0, NodeRole.SERVER), Map.entry(1, NodeRole.RELAY),
                    Map.entry(2, NodeRole.RELAY), Map.entry(3, NodeRole.RELAY),
                    Map.entry(4, NodeRole.CLIENT), Map.entry(5, NodeRole.CLIENT));

    /**
     * Scenario 3: two servers (ids 1 and 6 — the group decision for the
     * assignment's "at least 2 servers"), five clients (ids 4, 8-11), the
     * rest relays (ids 2, 3, 5, 7).
     */
    private static final Map<Integer, NodeRole> SCENARIO_3_ROLES = Map.ofEntries(
            Map.entry(1, NodeRole.SERVER), Map.entry(2, NodeRole.RELAY),
            Map.entry(3, NodeRole.RELAY), Map.entry(4, NodeRole.CLIENT),
            Map.entry(5, NodeRole.RELAY), Map.entry(6, NodeRole.SERVER),
            Map.entry(7, NodeRole.RELAY), Map.entry(8, NodeRole.CLIENT),
            Map.entry(9, NodeRole.CLIENT), Map.entry(10, NodeRole.CLIENT),
            Map.entry(11, NodeRole.CLIENT));

    @Test
    void cenario1CarriesTheAgreedRoles() throws IOException {
        assertRoles("cenario1.yaml", SCENARIO_1_ROLES);
    }

    @Test
    void cenario2CarriesTheAgreedRoles() throws IOException {
        assertRoles("cenario2.yaml", SCENARIO_2_ROLES);
    }

    @Test
    void cenario3CarriesTheAgreedTwoServers() throws IOException {
        assertRoles("cenario3.yaml", SCENARIO_3_ROLES);
    }

    @Test
    void cenario2DeclaresTheRealN17Address() throws IOException {
        Scenario scenario = parseFile("cenario2.yaml");
        assertEquals("10.1.11.17/24", nodeWithId(scenario, 5).ipAddr(),
                "n17 owns 10.1.11.17/24 per context/scenarios.md (the .12 was a typo)");
    }

    @Test
    void localhostVariantsUseTheirOwnPortAndLoopbackAddressPerNode() throws IOException {
        String[][] fileAndPort = {
                {"cenario1-localhost.yaml", "5000"},
                {"cenario2-localhost.yaml", "5001"},
                {"cenario3-localhost.yaml", "5002"},
        };
        for (String[] fileAnd : fileAndPort) {
            Scenario scenario = parseFile(fileAnd[0]);
            assertEquals(Integer.parseInt(fileAnd[1]), scenario.port(),
                    "overlay port of " + fileAnd[0]);
            for (Node node : scenario.nodes()) {
                assertEquals("127.0.0." + (node.id() + 1) + "/8", node.ipAddr(),
                        "id N gets 127.0.0.(N+1)/8 in " + fileAnd[0]);
            }
        }
    }

    @Test
    void localhostVariantsCarryTheSameRolesAsTheCoreScenarios() throws IOException {
        assertRoles("cenario1-localhost.yaml", SCENARIO_1_ROLES);
        assertRoles("cenario2-localhost.yaml", SCENARIO_2_ROLES);
        assertRoles("cenario3-localhost.yaml", SCENARIO_3_ROLES);
    }

    @Test
    void routingTableBuildsForEveryNodeIdOfEveryScenario() throws IOException {
        List<String> files = List.of(
                "cenario1.yaml", "cenario2.yaml", "cenario3.yaml",
                "cenario1-localhost.yaml", "cenario2-localhost.yaml", "cenario3-localhost.yaml");
        for (String file : files) {
            Scenario scenario = parseFile(file);
            for (Node node : scenario.nodes()) {
                assertNotNull(RoutingTable.from(scenario, node.id()),
                        "routing table of node " + node.id() + " in " + file);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Scenario parseYaml(String yaml) throws IOException {
        InputStream input = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        return ScenarioParser.parse(input);
    }

    private static Scenario parseFile(String fileName) throws IOException {
        return ScenarioParser.parse(Path.of("resorces", fileName));
    }

    private static Node nodeWithId(Scenario scenario, int nodeId) {
        return scenario.nodes().stream()
                .filter(node -> node.id() == nodeId)
                .findFirst()
                .orElseThrow();
    }

    private static void assertRoles(String fileName, Map<Integer, NodeRole> expectedRolesByNodeId)
            throws IOException {
        Scenario scenario = parseFile(fileName);
        assertEquals(expectedRolesByNodeId.size(), scenario.nodes().size(),
                "node count of " + fileName);
        for (Map.Entry<Integer, NodeRole> expected : expectedRolesByNodeId.entrySet()) {
            assertEquals(expected.getValue(), nodeWithId(scenario, expected.getKey()).role(),
                    "role of node " + expected.getKey() + " in " + fileName);
        }
    }
}
