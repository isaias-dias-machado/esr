package dev.pl36.scenario;

import java.io.IOException;

/**
 * Thrown when a scenario file is syntactically valid YAML but violates a
 * semantic rule: port out of range, duplicate node id, neighbour pointing at an
 * unknown node, self link, or the same link listed twice.
 *
 * <p>It extends {@link IOException} so callers that already handle parse
 * failures keep working unchanged.</p>
 */
public final class InvalidScenarioException extends IOException {

    private static final long serialVersionUID = 1L;

    public InvalidScenarioException(String message) {
        super(message);
    }
}
