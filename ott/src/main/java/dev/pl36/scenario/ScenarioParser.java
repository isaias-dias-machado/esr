package dev.pl36.scenario;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads scenario YAML files (for example {@code resorces/cenario1.yaml}) into
 * {@link Scenario} objects.
 *
 * <p>Invariants: the mapper is immutable after class initialisation and safe to
 * share between threads. Parsing is strict: unknown keys, missing keys and
 * {@code null} where a primitive is expected are reported as errors instead of
 * being silently ignored.</p>
 *
 * <p>Failure modes: all parse problems surface as {@link IOException}
 * subclasses: {@link com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException}
 * for unknown keys, {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}
 * for type/shape errors, and {@link InvalidScenarioException} when the file is
 * well-formed YAML but breaks a scenario rule (see {@link #validate(Scenario)}).</p>
 */
public final class ScenarioParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private ScenarioParser() {
        // Utility class: use the static parse methods.
    }

    /**
     * Purpose: parse a scenario from a file on disk.
     *
     * @param scenarioFile path to the YAML file
     * @return the parsed scenario, never {@code null}
     * @throws IOException if the file cannot be read or does not match the expected shape
     */
    public static Scenario parse(Path scenarioFile) throws IOException {
        return validate(YAML_MAPPER.readValue(scenarioFile.toFile(), Scenario.class));
    }

    /**
     * Purpose: parse a scenario from an already-open stream. The caller keeps
     * ownership of the stream and is responsible for closing it.
     *
     * @param yamlStream YAML content
     * @return the parsed scenario, never {@code null}
     * @throws IOException if the stream cannot be read or does not match the expected shape
     */
    public static Scenario parse(InputStream yamlStream) throws IOException {
        return validate(YAML_MAPPER.readValue(yamlStream, Scenario.class));
    }

    /**
     * Purpose: parse a scenario that is packaged on the classpath, e.g.
     * {@code "cenario1.yaml"} if it lives in {@code src/main/resources}.
     *
     * @param resourceName classpath-relative name of the YAML file
     * @return the parsed scenario, never {@code null}
     * @throws IOException if the resource is missing or does not match the expected shape
     */
    public static Scenario parseResource(String resourceName) throws IOException {
        try (InputStream yamlStream = ScenarioParser.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (yamlStream == null) {
                throw new FileNotFoundException("Classpath resource not found: " + resourceName);
            }
            return parse(yamlStream);
        }
    }

    /**
     * Purpose: check the rules that Jackson cannot express (port range, unique
     * node ids, neighbours referring to existing nodes, links listed once).
     *
     * @param scenario a structurally valid scenario
     * @return the same scenario, so callers can chain the call
     * @throws InvalidScenarioException if any rule is broken
     */
    static Scenario validate(Scenario scenario) throws InvalidScenarioException {
        if (scenario.port() < 0 || scenario.port() > 65535) {
            throw new InvalidScenarioException("port out of range 0..65535: " + scenario.port());
        }

        Set<Integer> nodeIds = new HashSet<>();
        for (Node node : scenario.nodes()) {
            if (!nodeIds.add(node.id())) {
                throw new InvalidScenarioException("duplicate node id: " + node.id());
            }
        }

        // Stored as "min-max" so that [0, 1] and [1, 0] compare equal.
        Set<String> seenLinks = new TreeSet<>();
        for (Neighbor neighbor : scenario.neighbors()) {
            int first = neighbor.first();
            int second = neighbor.second();
            if (first == second) {
                throw new InvalidScenarioException("node linked to itself: " + first);
            }
            if (!nodeIds.contains(first)) {
                throw new InvalidScenarioException("neighbour pair " + neighbor.pair()
                        + " refers to unknown node id " + first);
            }
            if (!nodeIds.contains(second)) {
                throw new InvalidScenarioException("neighbour pair " + neighbor.pair()
                        + " refers to unknown node id " + second);
            }
            String key = Math.min(first, second) + "-" + Math.max(first, second);
            if (!seenLinks.add(key)) {
                throw new InvalidScenarioException("link listed twice: " + neighbor.pair());
            }
        }
        return scenario;
    }
}
