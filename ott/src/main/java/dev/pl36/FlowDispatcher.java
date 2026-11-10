package dev.pl36;

import dev.pl36.net.ControlMessage;
import dev.pl36.net.MessageType;
import dev.pl36.net.api.ControlMessenger;
import dev.pl36.net.api.ControlMessengerListener;
import dev.pl36.net.api.DataChannel;
import dev.pl36.routing.FlowEntry;
import dev.pl36.streaming.MjpegVideoStream;
import dev.pl36.streaming.StreamSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The placeholder per-flow dispatcher of a server-mode OTT node.
 *
 * <p>Purpose: until the routing workstream's real {@code FlowTable} lands,
 * this class keeps the node's per-flow routing entries
 * ({@code { Flow, Cost, Origin, Destinations, State }}, see
 * {@link FlowEntry}) and drives one {@link StreamSource} per owned flow over
 * the shared {@link DataChannel}. It reacts to the strategy-neutral control
 * messages: a {@code WANT_FLOW} for an owned flow resolves the entry to
 * {@code Origin = LOCAL} and adds the requester to {@code Destinations}
 * (plural — every requester of the same flow is served by the same source,
 * with identical sequence numbers), starting the source on the first
 * request; {@code LEAVE_FLOW} removes the requester and stops the source
 * when the last one leaves. Messages are deduplicated by
 * {@code (fromNodeId, messageId)}, as controlled flooding requires.</p>
 *
 * <p>Invariants: it sees only the {@code net.api} seams and flow ids — never
 * sockets or addresses; the mapping from a requester's node id to its UDP
 * data address is delegated to the {@link DataPeerRegistrar} callback that
 * the composition root ({@code Main}) supplies. At most one source runs per
 * flow; a source's destinations are read live from its entry on every
 * frame. All listener callbacks are serialized on this instance's monitor,
 * so entry and source state cannot diverge.</p>
 *
 * <p>Failure modes: a {@code WANT_FLOW} for a flow this node does not own
 * is logged and ignored (flooding is the routing workstream's job); a video
 * file that cannot be opened is logged and the entry stays inactive; a null
 * flow id is ignored. Construction rejects null arguments.</p>
 */
public final class FlowDispatcher implements ControlMessengerListener {

    /**
     * The callback that maps a requester's node id to its data endpoint.
     *
     * <p>Purpose: keep address knowledge out of this class — in the
     * localhost placeholder {@code Main} implements it by combining the
     * control connection's peer host with the UDP listen port carried in
     * the {@code WANT_FLOW} payload and registering the result on the
     * {@code UdpDataChannel}; the in-JVM loopback tests use a recording
     * fake, because node ids need no addresses there.</p>
     */
    @FunctionalInterface
    public interface DataPeerRegistrar {

        /**
         * Purpose: register the data endpoint of a requester.
         *
         * @param nodeId       the requester's node id (the flow entry's
         *                     destination)
         * @param dataEndpoint the endpoint string from the WANT_FLOW payload
         */
        void registerDataPeer(int nodeId, String dataEndpoint);
    }

    private final ControlMessenger control;
    private final DataChannel dataChannel;
    private final Map<String, Path> flowVideos;
    private final long framePeriodMillis;
    private final DataPeerRegistrar peerRegistrar;
    private final Map<String, FlowEntry> entriesByFlowId = new ConcurrentHashMap<>();
    private final Map<String, StreamSource> sourcesByFlowId = new ConcurrentHashMap<>();
    private final Map<String, MjpegVideoStream> videosByFlowId = new ConcurrentHashMap<>();
    private final Set<SeenMessage> seenMessages = ConcurrentHashMap.newKeySet();
    private final AtomicLong announceMessageId = new AtomicLong(1L);

    /**
     * Purpose: create the dispatcher of a server node.
     *
     * @param control           the control messenger used for ANNOUNCE sends
     * @param dataChannel       the one shared data channel all sources send on
     * @param flowVideos        the owned flows: flow id to video file
     * @param framePeriodMillis the constant frame interval of every source
     * @param peerRegistrar     maps a requester node id to its data endpoint
     * @throws NullPointerException for a null argument
     */
    public FlowDispatcher(ControlMessenger control,
                          DataChannel dataChannel,
                          Map<String, Path> flowVideos,
                          long framePeriodMillis,
                          DataPeerRegistrar peerRegistrar) {
        this.control = Objects.requireNonNull(control, "control");
        this.dataChannel = Objects.requireNonNull(dataChannel, "dataChannel");
        this.flowVideos = Map.copyOf(Objects.requireNonNull(flowVideos, "flowVideos"));
        if (framePeriodMillis <= 0) {
            throw new IllegalArgumentException("frame period must be positive: " + framePeriodMillis);
        }
        this.framePeriodMillis = framePeriodMillis;
        this.peerRegistrar = Objects.requireNonNull(peerRegistrar, "peerRegistrar");
    }

    /** Purpose: the flow ids this node owns (the keys of {@code flowVideos}). */
    public Set<String> ownedFlows() {
        return flowVideos.keySet();
    }

    /** Purpose: an unmodifiable view of the per-flow routing entries. */
    public Map<String, FlowEntry> entries() {
        return Map.copyOf(entriesByFlowId);
    }

    /**
     * Purpose: the currently running source for {@code flowId}, or null —
     * used by tests to await end of video.
     */
    public synchronized StreamSource source(String flowId) {
        return sourcesByFlowId.get(flowId);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void onMessage(int fromNodeId, ControlMessage message) {
        switch (message.type()) {
            case WANT_FLOW -> onWantFlow(fromNodeId, message);
            case LEAVE_FLOW -> onLeaveFlow(fromNodeId, message);
            case ANNOUNCE -> System.out.printf("dispatcher: ANNOUNCE from node %d: flow '%s'%n",
                    fromNodeId, message.flowId());
            default -> System.out.printf("dispatcher: ignoring %s from node %d%n",
                    message.type(), fromNodeId);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The server announces every flow it owns to a newly connected
     * neighbour (Stage 2, Strategy 1 shape); the request model needs none
     * of it, but the message travels either way.</p>
     */
    @Override
    public synchronized void onNeighborUp(int nodeId) {
        for (String flowId : flowVideos.keySet()) {
            control.send(nodeId, new ControlMessage(
                    MessageType.ANNOUNCE, announceMessageId.getAndIncrement(), flowId, ""));
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The departed neighbour is removed from every flow's destinations;
     * a flow left without destinations stops its source (connection-death
     * detection proper is the transport workstream's job later).</p>
     */
    @Override
    public synchronized void onNeighborDown(int nodeId) {
        for (String flowId : List.copyOf(entriesByFlowId.keySet())) {
            FlowEntry entry = entriesByFlowId.get(flowId);
            if (entry != null && entry.destinations().contains(nodeId)) {
                updateEntry(flowId, entry.withDestinationRemoved(nodeId));
            }
        }
    }

    /**
     * Purpose: resolve a WANT_FLOW — dedup, then add the requester to the
     * flow's destinations and start the source if none is running.
     */
    private void onWantFlow(int fromNodeId, ControlMessage message) {
        if (!seenMessages.add(new SeenMessage(fromNodeId, message.messageId()))) {
            System.out.printf("dispatcher: duplicate WANT_FLOW %d from node %d, ignored%n",
                    message.messageId(), fromNodeId);
            return;
        }
        String flowId = message.flowId();
        if (flowId == null || !flowVideos.containsKey(flowId)) {
            System.out.printf("dispatcher: WANT_FLOW for unknown flow '%s' from node %d, ignored%n",
                    flowId, fromNodeId);
            return;
        }

        peerRegistrar.registerDataPeer(fromNodeId, message.payload());

        FlowEntry entry = entriesByFlowId.get(flowId);
        FlowEntry updated = (entry == null)
                ? FlowEntry.local(flowId, List.of(fromNodeId))
                : entry.withDestinationAdded(fromNodeId);
        updateEntry(flowId, updated);

        if (!sourcesByFlowId.containsKey(flowId)) {
            startSource(flowId);
        }
    }

    /**
     * Purpose: withdraw a WANT_FLOW — remove the requester from the flow's
     * destinations; stop the source when the last destination leaves.
     */
    private void onLeaveFlow(int fromNodeId, ControlMessage message) {
        if (!seenMessages.add(new SeenMessage(fromNodeId, message.messageId()))) {
            return;
        }
        String flowId = message.flowId();
        FlowEntry entry = flowId == null ? null : entriesByFlowId.get(flowId);
        if (entry == null) {
            return;
        }
        updateEntry(flowId, entry.withDestinationRemoved(fromNodeId));
    }

    /**
     * Purpose: store the new entry and stop the source when the entry went
     * inactive; print the entry, so the flow table is visible live.
     */
    private void updateEntry(String flowId, FlowEntry updated) {
        entriesByFlowId.put(flowId, updated);
        System.out.printf("flow-table: %s%n", updated);
        if (updated.state() == FlowEntry.State.INACTIVE) {
            stopSource(flowId);
        }
    }

    /**
     * Purpose: open the video and start the timer-driven source for
     * {@code flowId}, with destinations read live from the flow entry.
     */
    private void startSource(String flowId) {
        final MjpegVideoStream video;
        try {
            video = new MjpegVideoStream(flowVideos.get(flowId));
        } catch (IOException openFailure) {
            System.out.printf("dispatcher: cannot open video for flow '%s': %s%n",
                    flowId, openFailure.getMessage());
            return;
        }
        StreamSource source = new StreamSource(flowId, video, dataChannel,
                () -> currentDestinations(flowId), framePeriodMillis);
        videosByFlowId.put(flowId, video);
        sourcesByFlowId.put(flowId, source);
        source.start();
        System.out.printf("dispatcher: started source for flow '%s' (%d frame period ms)%n",
                flowId, framePeriodMillis);
    }

    /**
     * Purpose: stop and close the source and video of {@code flowId}, if
     * running. Idempotent.
     */
    private void stopSource(String flowId) {
        StreamSource source = sourcesByFlowId.remove(flowId);
        if (source != null) {
            source.stop();
        }
        MjpegVideoStream video = videosByFlowId.remove(flowId);
        if (video != null) {
            try {
                video.close();
            } catch (IOException closeFailure) {
                System.out.printf("dispatcher: closing video for flow '%s' failed: %s%n",
                        flowId, closeFailure.getMessage());
            }
        }
        if (source != null) {
            System.out.printf("dispatcher: stopped source for flow '%s' (%d frame(s) sent)%n",
                    flowId, source.framesSent());
        }
    }

    /** Purpose: the live destinations of {@code flowId} for the source supplier. */
    private List<Integer> currentDestinations(String flowId) {
        FlowEntry entry = entriesByFlowId.get(flowId);
        return entry == null ? List.of() : entry.destinations();
    }

    /** Dedup key of a flooded control message: unique per source process. */
    private record SeenMessage(int fromNodeId, long messageId) {
    }
}
