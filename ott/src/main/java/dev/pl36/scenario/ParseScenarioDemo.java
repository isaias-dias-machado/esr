package dev.pl36.scenario;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Small runnable example: parses a scenario file and prints its nodes.
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
            System.out.printf("Loaded %d node(s) from %s%n", scenario.nodes().size(), scenarioFile);
            for (Node node : scenario.nodes()) {
                System.out.printf("  id=%d ip=%s port=%d description=%s%n",
                        node.id(),
                        node.ipAddr(),
                        node.port(),
                        node.description());
            }
        } catch (IOException cause) {
            System.err.printf("Could not parse %s: %s%n", scenarioFile, cause.getMessage());
            System.exit(1);
        }
    }
}
