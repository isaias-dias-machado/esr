package dev.pl36;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Purpose: verify the CLI flag parsing — old single-flag invocations parse
 * identically, {@code --video}/{@code --want}/{@code --flow} repeat, and the
 * flow-id rules (bare file name by default, paired {@code --flow} overrides)
 * behave as documented in the HELP text.
 */
class MainFlagsTest {

    @Test
    void oldSingleFlagInvocationsParseIdentically() {
        Map<String, List<String>> serverFlags =
                Main.parseFlags(new String[]{"server", "--dest", "127.0.0.1:25000", "--fps", "24"});
        assertEquals(List.of("127.0.0.1:25000"), serverFlags.get("dest"));
        assertEquals("24", Main.singleValue(serverFlags, "fps", "10"));
        assertEquals(Map.of("movie.Mjpeg", Path.of("resorces/movie.Mjpeg")), Main.ownedFlows(serverFlags));

        Map<String, List<String>> clientFlags = Main.parseFlags(new String[]{"client", "--listen", "25000"});
        assertEquals("25000", Main.singleValue(clientFlags, "listen", "25000"));
        assertEquals(List.of("movie.Mjpeg"), Main.wantedFlows(clientFlags));
    }

    @Test
    void repeatedVideoFlagsGiveOneFlowPerFileName() {
        Map<String, List<String>> flags = Main.parseFlags(new String[]{
                "server", "--video", "resorces/movie.Mjpeg", "--video", "resorces/movie2.Mjpeg"});
        assertEquals(List.of("resorces/movie.Mjpeg", "resorces/movie2.Mjpeg"), flags.get("video"));
        assertEquals(
                Map.of("movie.Mjpeg", Path.of("resorces/movie.Mjpeg"),
                        "movie2.Mjpeg", Path.of("resorces/movie2.Mjpeg")),
                Main.ownedFlows(flags));
    }

    @Test
    void flowOverridesPairWithVideosByPosition() {
        Map<String, List<String>> flags = Main.parseFlags(new String[]{
                "server",
                "--video", "resorces/movie.Mjpeg", "--video", "resorces/movie2.Mjpeg",
                "--flow", "serverA/movie.Mjpeg", "--flow", "serverB/movie.Mjpeg"});
        assertEquals(
                Map.of("serverA/movie.Mjpeg", Path.of("resorces/movie.Mjpeg"),
                        "serverB/movie.Mjpeg", Path.of("resorces/movie2.Mjpeg")),
                Main.ownedFlows(flags));
    }

    @Test
    void mismatchedFlowOverrideCountIsRejected() {
        Map<String, List<String>> flags = Main.parseFlags(new String[]{
                "server", "--video", "a.Mjpeg", "--video", "b.Mjpeg", "--flow", "only-one"});
        assertThrows(IllegalArgumentException.class, () -> Main.ownedFlows(flags));
    }

    @Test
    void wantIsAnAliasOfFlowOnTheClient() {
        Map<String, List<String>> flags = Main.parseFlags(new String[]{
                "client", "--want", "movie.Mjpeg", "--flow", "movie2.Mjpeg", "--listen", "25000"});
        assertEquals(List.of("movie.Mjpeg", "movie2.Mjpeg"), Main.wantedFlows(flags));
    }

    @Test
    void singleValueFlagsKeepLastValue() {
        Map<String, List<String>> flags =
                Main.parseFlags(new String[]{"client", "--listen", "1", "--listen", "25000"});
        assertEquals("25000", Main.singleValue(flags, "listen", "0"));
    }

    @Test
    void malformedFlagsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseFlags(new String[]{"server", "--video"}));
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseFlags(new String[]{"server", "video", "x"}));
    }
}
