package dev.pl36;

import dev.pl36.net.ControlMessage;
import dev.pl36.net.MessageType;
import dev.pl36.net.TcpControlLink;
import dev.pl36.net.UdpDataChannel;
import dev.pl36.net.api.ControlMessengerListener;
import dev.pl36.net.api.DataChannelListener;
import dev.pl36.routing.FlowEntry;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * The single deployable OTT program. Three modes:
 *
 * <ul>
 *   <li>{@code ott <node_id> <scenario.yaml>} — overlay node: parses the
 *       scenario and prints the local routing table.</li>
 *   <li>{@code ott server [--video file]... [--flow id]... [--fps n]
 *       [--control port] [--dest host:port]} — an OTT node that <em>owns</em>
 *       flows (one per {@code --video}, flow id = video file name unless
 *       overridden by {@code --flow}) and answers {@code WANT_FLOW} on the
 *       TCP control port (default 25001), streaming to every requester.
 *       {@code --dest} survives only as a test-only push override that
 *       streams to one fixed address immediately, without a control
 *       channel.</li>
 *   <li>{@code ott client [--listen port] [--server host:port] [--want flow]...
 *       [--flow flow]...} — names <em>content</em>, not a server: sends one
 *       {@code WANT_FLOW} per wanted flow to the server's control channel
 *       (default 127.0.0.1:25001) and logs every frame and gap of every
 *       wanted flow arriving on the UDP listen port.</li>
 * </ul>
 *
 * <p>All streaming defaults are hardcoded (the ProgEx values) and overridable
 * by CLI flags: push destination {@code 127.0.0.1:25000}, video
 * {@code resorces/movie.Mjpeg}, flow {@code movie.Mjpeg} (the bare file name),
 * 10 fps, client listen port 25000, control port 25001. {@code --video},
 * {@code --want} and {@code --flow} repeat; {@code --want} is an alias of
 * {@code --flow}.</p>
 */
public class Main {

    /** Default push destination of the test-only {@code --dest} override, as in the ProgEx examples. */
    private static final String DEFAULT_DESTINATION = "127.0.0.1:25000";

    /** Default video file, relative to the ott working directory. */
    private static final String DEFAULT_VIDEO_FILE = "resorces/movie.Mjpeg";

    /** Default flow id: the bare file name of the default video (content-addressed). */
    private static final String DEFAULT_FLOW_ID = "movie.Mjpeg";

    /** Default client listen port. */
    private static final int DEFAULT_LISTEN_PORT = 25000;

    /** Default frame rate of the streamed videos. */
    private static final int DEFAULT_FPS = 10;

    /** Default TCP port of the server's control channel. */
    private static final int DEFAULT_CONTROL_PORT = 25001;

    /** Default control address the client sends WANT_FLOW to. */
    private static final String DEFAULT_SERVER_ADDRESS = "127.0.0.1:" + DEFAULT_CONTROL_PORT;

    /** Synthetic node id of the server process in the localhost placeholder. */
    private static final int SERVER_NODE_ID = 0;

    /** Synthetic node id of a client process in the localhost placeholder. */
    private static final int CLIENT_NODE_ID = 1;

    /** First synthetic node id the server assigns to an accepted control connection. */
    private static final int FIRST_CLIENT_NODE_ID = 1;

    /** Flags that may repeat and therefore collect a list of values. */
    private static final Set<String> MULTI_VALUE_FLAGS = Set.of("video", "want", "flow");

    private static final String HELP = """
            usage:
              ott <node_id> <scenario.yaml>            overlay node: parse scenario, print routing table
              ott server [--video file]... [--flow id]... [--fps n] [--control port] [--dest host:port]
                                                       serve flows (id = video file name), answer WANT_FLOW on
                                                       the control port (default 25001); --dest is a test-only
                                                       push override that streams to one address immediately
              ott client [--listen port] [--server host:port] [--want flow]... [--flow flow]...
                                                       request flows (default movie.Mjpeg) on the server's control
                                                       channel (default 127.0.0.1:25001), log frames/gaps on the
                                                       listen port (default 25000); --want is an alias of --flow
            defaults: video=resorces/movie.Mjpeg flow=movie.Mjpeg fps=10 listen=25000 control=25001 dest=127.0.0.1:25000
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
     * Purpose: server mode. Without {@code --dest}, run in request mode: own
     * the {@code --video} flows and answer WANT_FLOW on the control port,
     * streaming each requested flow to every requester. With {@code --dest},
     * run the legacy test-only push override: stream immediately to that one
     * address.
     *
     * @param flags CLI flags: video, flow, fps, control, dest
     */
    private static void runServer(Map<String, List<String>> flags) throws Exception {
        Map<String, Path> ownedFlows = ownedFlows(flags);
        int fps = parsePositiveInt(singleValue(flags, "fps", String.valueOf(DEFAULT_FPS)), "fps");
        long framePeriodMillis = 1000L / fps;
        String destination = singleValue(flags, "dest", null);

        if (destination != null) {
            runServerPush(ownedFlows, parseAddress(destination), framePeriodMillis);
        } else {
            int controlPort = parsePositiveInt(
                    singleValue(flags, "control", String.valueOf(DEFAULT_CONTROL_PORT)), "control");
            runServerRequest(ownedFlows, controlPort, framePeriodMillis);
        }
    }

    /**
     * Purpose: request mode — listen for WANT_FLOW on the control port and
     * serve every owned flow to every requester through one shared UDP data
     * channel. Runs until killed.
     */
    private static void runServerRequest(Map<String, Path> ownedFlows, int controlPort,
                                         long framePeriodMillis) throws Exception {
        TcpControlLink controlLink = TcpControlLink.listen(SERVER_NODE_ID, controlPort, FIRST_CLIENT_NODE_ID);
        UdpDataChannel dataChannel = new UdpDataChannel(0, Map.of());

        FlowDispatcher dispatcher = new FlowDispatcher(controlLink, dataChannel, ownedFlows,
                framePeriodMillis, (nodeId, dataEndpoint) -> {
                    try {
                        int dataPort = Integer.parseInt(dataEndpoint);
                        InetSocketAddress controlPeer = controlLink.peerAddress(nodeId);
                        dataChannel.addPeer(nodeId,
                                new InetSocketAddress(controlPeer.getAddress(), dataPort));
                    } catch (RuntimeException badEndpoint) {
                        System.out.printf("server: bad data endpoint '%s' from node %d: %s%n",
                                dataEndpoint, nodeId, badEndpoint.getMessage());
                    }
                });

        controlLink.setListener(dispatcher);
        dataChannel.start();
        controlLink.start();

        System.out.printf("server: owns flows %s; control port %d; data port %d; waiting for WANT_FLOW%n",
                dispatcher.ownedFlows(), controlPort, dataChannel.localPort());
        new CountDownLatch(1).await();
    }

    /**
     * Purpose: the test-only push override — stream every owned flow at the
     * configured rate to the single fixed {@code destination}, without a
     * control channel, as the original ProgEx-style demo did.
     */
    private static void runServerPush(Map<String, Path> ownedFlows, InetSocketAddress destination,
                                      long framePeriodMillis) throws Exception {
        UdpDataChannel channel = UdpDataChannel.singlePeer(0, CLIENT_NODE_ID, destination);
        channel.start();
        List<MjpegVideoStream> videos = new ArrayList<>();
        List<StreamSource> sources = new ArrayList<>();
        try {
            for (Map.Entry<String, Path> ownedFlow : ownedFlows.entrySet()) {
                MjpegVideoStream video = new MjpegVideoStream(ownedFlow.getValue());
                videos.add(video);
                StreamSource source = new StreamSource(ownedFlow.getKey(), video, channel,
                        List.of(CLIENT_NODE_ID), framePeriodMillis);
                sources.add(source);
                System.out.printf("server: streaming %s as flow '%s' (%d ms/frame) to %s (push override)%n",
                        ownedFlow.getValue(), ownedFlow.getKey(), framePeriodMillis, destination);
                System.out.println("flow-table: " + FlowEntry.local(ownedFlow.getKey(), List.of(CLIENT_NODE_ID)));
                source.start();
            }
            for (StreamSource source : sources) {
                source.awaitTermination();
                System.out.printf("server: end of video, %d frame(s) sent%n", source.framesSent());
            }
        } finally {
            for (MjpegVideoStream video : videos) {
                video.close();
            }
            channel.close();
        }
    }

    /**
     * Purpose: client mode — name the wanted flows (content, not a server),
     * send one WANT_FLOW per flow to the server's control channel, and log
     * every frame and gap of every wanted flow arriving on the UDP listen
     * port. Runs until killed. If the server has no control channel (push
     * override mode), it just listens.
     *
     * @param flags CLI flags: listen, server, want, flow
     */
    private static void runClient(Map<String, List<String>> flags) throws Exception {
        int listenPort = parsePositiveInt(
                singleValue(flags, "listen", String.valueOf(DEFAULT_LISTEN_PORT)), "listen");
        String serverAddress = singleValue(flags, "server", DEFAULT_SERVER_ADDRESS);
        List<String> wantedFlows = wantedFlows(flags);

        UdpDataChannel channel = new UdpDataChannel(listenPort, Map.of());
        List<StreamSink> sinks = new ArrayList<>();
        for (String flowId : wantedFlows) {
            sinks.add(new StreamSink(flowId, new ConsoleFrameHandler()));
        }
        // The demultiplexer: fan every inbound datagram out to all sinks on the
        // channel's single receiver thread; each sink filters by its flow id
        // (that filter is the demux). Console logging is fast enough here; if it
        // ever throttles the receiver, hand off per sink instead.
        channel.setListener((DataChannelListener) (fromNodeId, packet) -> {
            for (StreamSink sink : sinks) {
                sink.onDatagram(fromNodeId, packet);
            }
        });
        channel.start();

        System.out.printf("client: listening on port %d for flows %s%n", listenPort, wantedFlows);

        try {
            TcpControlLink controlLink = TcpControlLink.connect(
                    CLIENT_NODE_ID, SERVER_NODE_ID, parseAddress(serverAddress));
            controlLink.setListener(new ControlMessengerListener() {
                @Override
                public void onMessage(int fromNodeId, ControlMessage message) {
                    if (message.type() == MessageType.ANNOUNCE) {
                        System.out.printf("client: server node %d announces flow '%s'%n",
                                fromNodeId, message.flowId());
                    }
                }

                @Override
                public void onNeighborUp(int nodeId) {
                    System.out.printf("client: control link to node %d up%n", nodeId);
                }

                @Override
                public void onNeighborDown(int nodeId) {
                    System.out.printf("client: control link to node %d down%n", nodeId);
                }
            });
            controlLink.start();
            long messageId = 1L;
            for (String flowId : wantedFlows) {
                controlLink.send(SERVER_NODE_ID, new ControlMessage(
                        MessageType.WANT_FLOW, messageId++, flowId, String.valueOf(listenPort)));
            }
            System.out.printf("client: requested flows %s from %s%n", wantedFlows, serverAddress);
        } catch (IOException noControlChannel) {
            System.out.printf("client: no control channel at %s (%s) — listening only%n",
                    serverAddress, noControlChannel.getMessage());
        }

        new CountDownLatch(1).await();
    }

    /**
     * Purpose: parse {@code --flag value} pairs from {@code args[1..]} into a
     * map of value lists. The multi-value flags ({@code --video},
     * {@code --want}, {@code --flow}) accumulate every occurrence; every
     * other flag keeps the last value, as before. Unknown shapes are
     * rejected.
     */
    static Map<String, List<String>> parseFlags(String[] args) {
        Map<String, List<String>> flags = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (!flag.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException(HELP);
            }
            String name = flag.substring(2);
            String value = args[++i];
            if (MULTI_VALUE_FLAGS.contains(name)) {
                flags.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            } else {
                flags.put(name, new ArrayList<>(List.of(value)));
            }
        }
        return flags;
    }

    /**
     * Purpose: the repeated values of a multi-value flag, or an empty list.
     */
    static List<String> multiValue(Map<String, List<String>> flags, String name) {
        return flags.getOrDefault(name, List.of());
    }

    /**
     * Purpose: the value of a single-value flag (the last one wins), or
     * {@code defaultValue} when the flag is absent.
     */
    static String singleValue(Map<String, List<String>> flags, String name, String defaultValue) {
        List<String> values = flags.get(name);
        return values == null || values.isEmpty() ? defaultValue : values.get(values.size() - 1);
    }

    /**
     * Purpose: the flows a client wants — every {@code --want} and
     * {@code --flow} value ({@code --want} is an alias of {@code --flow}),
     * defaulting to the bare file name of the default video.
     */
    static List<String> wantedFlows(Map<String, List<String>> flags) {
        List<String> wants = new ArrayList<>(multiValue(flags, "want"));
        wants.addAll(multiValue(flags, "flow"));
        return wants.isEmpty() ? List.of(DEFAULT_FLOW_ID) : List.copyOf(wants);
    }

    /**
     * Purpose: the flows a server owns — one per {@code --video}, keyed by
     * the video's file name unless {@code --flow} overrides the ids (then it
     * must repeat exactly once per {@code --video}, to disambiguate two
     * servers serving the same file name).
     *
     * @throws IllegalArgumentException if {@code --flow} neither is omitted
     *                                  nor matches the {@code --video} count
     */
    static Map<String, Path> ownedFlows(Map<String, List<String>> flags) {
        List<String> videos = multiValue(flags, "video");
        if (videos.isEmpty()) {
            videos = List.of(DEFAULT_VIDEO_FILE);
        }
        List<String> flowOverrides = multiValue(flags, "flow");
        Map<String, Path> ownedFlows = new LinkedHashMap<>();
        if (flowOverrides.isEmpty()) {
            for (String video : videos) {
                ownedFlows.put(Path.of(video).getFileName().toString(), Path.of(video));
            }
        } else if (flowOverrides.size() == videos.size()) {
            for (int i = 0; i < videos.size(); i++) {
                ownedFlows.put(flowOverrides.get(i), Path.of(videos.get(i)));
            }
        } else {
            throw new IllegalArgumentException(
                    "--flow must be omitted or repeat once per --video: " + flowOverrides.size()
                            + " flow id(s) for " + videos.size() + " video(s)");
        }
        return ownedFlows;
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
