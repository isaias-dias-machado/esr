package dev.pl36;

import dev.pl36.net.UdpDataChannel;
import dev.pl36.net.api.DataChannelListener;
import dev.pl36.routing.RoutingTable;
import dev.pl36.scenario.Scenario;
import dev.pl36.scenario.ScenarioParser;
import dev.pl36.streaming.ConsoleFrameHandler;
import dev.pl36.streaming.MjpegVideoStream;
import dev.pl36.streaming.StreamSink;
import dev.pl36.streaming.StreamSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The single deployable OTT program. Three modes:
 *
 * <ul>
 *   <li>{@code ott <node_id> <scenario.yaml>} — overlay node: parses the
 *       scenario and prints the local routing table.</li>
 *   <li>{@code ott server [--dest host:port] [--video file] [--flow id] [--fps n]}
 *       — streams one video to one client over UDP, at a constant frame
 *       rate.</li>
 *   <li>{@code ott client [--listen port] [--server host:port] [--flow id]}
 *       — receives one flow and logs every frame and every gap.</li>
 * </ul>
 *
 * <p>All streaming defaults are hardcoded (the ProgEx values) and overridable
 * by CLI flags: destination {@code 127.0.0.1:25000}, video
 * {@code resorces/movie.Mjpeg}, flow {@code server1/movie.Mjpeg}, 10 fps,
 * client listen port 25000.</p>
 */
public class Main {

    /** Default client endpoint the server streams to, as in the ProgEx examples. */
    private static final String DEFAULT_DESTINATION = "127.0.0.1:25000";

    /** Default video file, relative to the ott working directory. */
    private static final String DEFAULT_VIDEO_FILE = "resorces/movie.Mjpeg";

    /** Default flow id, of the form "server/movie". */
    private static final String DEFAULT_FLOW_ID = "server1/movie.Mjpeg";

    /** Default client listen port. */
    private static final int DEFAULT_LISTEN_PORT = 25000;

    /** Default frame rate of the streamed video. */
    private static final int DEFAULT_FPS = 10;

    /** Synthetic node id of the single client in server mode. */
    private static final int CLIENT_NODE_ID = 1;

    private static final String HELP = """
            usage:
              ott <node_id> <scenario.yaml>       overlay node: parse scenario, print routing table
              ott server [--dest host:port] [--video file] [--flow id] [--fps n]
                                                  stream a video to one client (default dest 127.0.0.1:25000)
              ott client [--listen port] [--server host:port] [--flow id]
                                                  receive a flow and log frames/gaps (default listen 25000)
            defaults: video=resorces/movie.Mjpeg flow=server1/movie.Mjpeg fps=10
            """;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException(HELP);
        }
        switch (args[0]) {
            case "server" -> runServer(parseFlags(args));
            case "client" -> runClient(parseFlags(args));
            default -> runNode(args);
        }
    }

    /**
     * Purpose: overlay node mode — {@code ott <node_id> <scenario.yaml>}.
     * Parses the scenario and prints the local routing table.
     */
    private static void runNode(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(HELP);
        }

        int selfId = parseNodeId(args[0]);
        Path scenarioPath = Path.of(args[1]);

        try {
            Scenario scenario = ScenarioParser.parse(scenarioPath);
            RoutingTable routingTable = RoutingTable.from(scenario, selfId);
            System.out.println(routingTable);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Purpose: server mode — read the video file and stream it, frame by
     * frame at the configured rate, to the single client destination.
     *
     * @param flags CLI flags: dest, video, flow, fps
     */
    private static void runServer(Map<String, String> flags) throws Exception {
        InetSocketAddress destination = parseAddress(flags.getOrDefault("dest", DEFAULT_DESTINATION));
        Path videoFile = Path.of(flags.getOrDefault("video", DEFAULT_VIDEO_FILE));
        String flowId = flags.getOrDefault("flow", DEFAULT_FLOW_ID);
        int fps = parsePositiveInt(flags.getOrDefault("fps", String.valueOf(DEFAULT_FPS)), "fps");

        UdpDataChannel channel = UdpDataChannel.singlePeer(0, CLIENT_NODE_ID, destination);
        channel.start();
        try (MjpegVideoStream video = new MjpegVideoStream(videoFile)) {
            StreamSource source = new StreamSource(flowId, video, channel, List.of(CLIENT_NODE_ID),
                    1000L / fps);
            System.out.printf(
                    "server: streaming %s as flow '%s' at %d fps to %s (bound to ephemeral port %d)%n",
                    videoFile, flowId, fps, destination, channel.localPort());
            source.start();
            source.awaitTermination();
            System.out.printf("server: end of video, %d frame(s) sent%n", source.framesSent());
        } finally {
            channel.close();
        }
    }

    /**
     * Purpose: client mode — bind the listen port, decode incoming datagrams
     * as the requested flow, and log every frame and gap until killed.
     *
     * @param flags CLI flags: listen, server, flow
     */
    private static void runClient(Map<String, String> flags) throws Exception {
        int listenPort = parsePositiveInt(flags.getOrDefault("listen", String.valueOf(DEFAULT_LISTEN_PORT)),
                "listen");
        String serverAddress = flags.getOrDefault("server", DEFAULT_DESTINATION);
        String flowId = flags.getOrDefault("flow", DEFAULT_FLOW_ID);

        UdpDataChannel channel = new UdpDataChannel(listenPort, Map.of());
        StreamSink sink = new StreamSink(flowId, new ConsoleFrameHandler());
        channel.setListener((DataChannelListener) sink::onDatagram);
        channel.start();

        System.out.printf("client: listening on port %d for flow '%s' (server %s)%n",
                listenPort, flowId, serverAddress);
        new CountDownLatch(1).await();
    }

    /**
     * Purpose: parse {@code --flag value} pairs from {@code args[1..]} into a
     * map; unknown shapes are rejected.
     */
    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (!flag.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException(HELP);
            }
            flags.put(flag.substring(2), args[++i]);
        }
        return flags;
    }

    /**
     * Purpose: parse a {@code host:port} CLI value.
     *
     * @throws IllegalArgumentException if the text is malformed or the host
     *                                  does not resolve
     */
    private static InetSocketAddress parseAddress(String hostPort) {
        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex < 1 || colonIndex == hostPort.length() - 1) {
            throw new IllegalArgumentException("expected host:port, got: " + hostPort);
        }
        String host = hostPort.substring(0, colonIndex);
        int port = parsePositiveInt(hostPort.substring(colonIndex + 1), "port");
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("cannot resolve host: " + host);
        }
        return address;
    }

    /**
     * Purpose: parse a positive integer CLI value.
     *
     * @throws IllegalArgumentException if the text is not a positive integer
     */
    private static int parsePositiveInt(String raw, String what) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0 || ("port".equals(what) && value > 65535)) {
                throw new NumberFormatException("out of range");
            }
            return value;
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(what + " must be a positive integer, got: " + raw, cause);
        }
    }

    /**
     * Purpose: parse a scenario node id from the CLI.
     *
     * @throws IllegalArgumentException if the text is not an integer
     */
    private static int parseNodeId(String rawNodeId) {
        try {
            return Integer.parseInt(rawNodeId);
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException(
                    "node id must be an integer, got: " + rawNodeId, cause);
        }
    }
}
