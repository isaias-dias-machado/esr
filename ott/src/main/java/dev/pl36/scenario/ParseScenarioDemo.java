package dev.pl36.scenario;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Small runnable example: parses a scenario file and prints its nodes and links.
 *
 * <p>Usage: {@code java dev.pl36.scenario.ParseScenarioDemo [path/to/scenario.yaml]}
 * (defaults to {@code resorces/cenario1.yaml}).</p>
 */
public final class ParseScenarioDemo {

    private ParseScenarioDemo() {
    }

    public static void main(String[] args) {
        Path scenarioFile = Path.of(args.length > 0 ? args[0] : "resorces/cenario1.yaml");

        try {
            Scenario scenario = ScenarioParser.parse(scenarioFile);
            System.out.printf("Loaded scenario from %s (port %d)%n", scenarioFile, scenario.port());

            System.out.printf("  %d node(s)%n", scenario.nodes().size());
            for (Node node : scenario.nodes()) {
                System.out.printf("    id=%d ip=%s description=%s%n",
                        node.id(),
                        node.ipAddr(),
                        node.description());
            }

            System.out.printf("  %d link(s)%n", scenario.neighbors().size());
            for (Neighbor neighbor : scenario.neighbors()) {
                System.out.printf("    %d <-> %d%n", neighbor.first(), neighbor.second());
            }
        } catch (IOException cause) {
            System.err.printf("Could not parse %s: %s%n", scenarioFile, cause.getMessage());
            System.exit(1);
        }
    }
}
