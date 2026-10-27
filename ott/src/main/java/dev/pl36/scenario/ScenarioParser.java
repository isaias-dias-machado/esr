package dev.pl36.scenario;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Reads scenario YAML files (for example {@code resorces/cenario1.yaml}) into
 * {@link Scenario} objects.
 *
 * <p>Invariants: the mapper is immutable after class initialisation and safe to
 * share between threads. Parsing is strict: unknown keys, missing keys and
 * {@code null} where a primitive is expected are reported as errors instead of
 * being silently ignored.</p>
 *
 * <p>Failure modes: all parse problems surface as {@link IOException} subclasses
 * ({@link com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException} for
 * unknown keys, {@link com.fasterxml.jackson.databind.exc.MismatchedInputException}
 * for type/shape errors, and so on).</p>
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
        return YAML_MAPPER.readValue(scenarioFile.toFile(), Scenario.class);
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
        return YAML_MAPPER.readValue(yamlStream, Scenario.class);
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
}
