package dev.pl36.streaming;

import dev.pl36.net.DataPacketHeader;
import dev.pl36.net.api.DataChannel;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The sending end of one stream, extracted from the book code's
 * {@code Servidor}.
 *
 * <p>Purpose: read frames from a {@link MjpegVideoStream} at a constant frame
 * rate and push each one, numbered per flow via {@link DataPacketHeader}, to
 * every destination over a {@link DataChannel}. Stops by itself at end of
 * file.</p>
 *
 * <p>Invariants: sequence numbers start at 1 and increase by one per frame;
 * every current destination receives the same datagram bytes;
 * {@link #start()} is timer-driven while {@link #pumpAll()} is synchronous
 * (tests and demos) — at most one of them runs at a time per instance. The
 * destination list may be <em>live</em> (the supplier constructor): it is
 * read again for every frame, so a destination added while the source runs
 * is served from the next frame on, without restarting the sequence numbers
 * the existing destinations already see.</p>
 *
 * <p>Failure modes: an {@link IOException} from the video file stops the
 * source and propagates out of {@link #sendNextFrame()}; a destination whose
 * link is down (the channel's {@link IllegalStateException}) is logged and
 * skipped for that frame — best effort like UDP, the other destinations are
 * unaffected. Construction rejects a null argument, an empty destination
 * list (fixed-list constructor), or a non-positive frame period.</p>
 */
public final class StreamSource {

    /** Default frame period: 100 ms, i.e. 10 fps, as in the ProgEx examples. */
    public static final long DEFAULT_FRAME_PERIOD_MILLIS = 100L;

    private final String flowId;
    private final MjpegVideoStream video;
    private final DataChannel channel;
    private final Supplier<? extends Collection<Integer>> destinationSupplier;
    private final long framePeriodMillis;
    private final CountDownLatch doneLatch = new CountDownLatch(1);

    private long nextSequenceNumber = 1L;
    private long framesSent;
    private ScheduledExecutorService scheduler;

    /**
     * Purpose: create a source that streams {@code video} as {@code flowId}
     * to a fixed set of destinations.
     *
     * @param flowId             the flow id stamped on every packet
     * @param video              the open video reader to pull frames from
     * @param channel            the data channel to send datagrams on
     * @param destinationNodeIds the neighbours to replicate each frame to
     * @param framePeriodMillis  the constant interval between frames, in ms
     * @throws NullPointerException     for a null argument
     * @throws IllegalArgumentException for an empty destination list or a
     *                                  non-positive frame period
     */
    public StreamSource(String flowId,
                        MjpegVideoStream video,
                        DataChannel channel,
                        Collection<Integer> destinationNodeIds,
                        long framePeriodMillis) {
        this(flowId, video, channel, fixedDestinations(destinationNodeIds), framePeriodMillis);
    }

    /**
     * Purpose: create a source whose destinations are read live from
     * {@code destinationSupplier} on every frame — the placeholder
     * dispatcher uses this so a WANT_FLOW that arrives mid-stream adds the
     * requester to the running source instead of restarting it.
     *
     * @param flowId              the flow id stamped on every packet
     * @param video               the open video reader to pull frames from
     * @param channel             the data channel to send datagrams on
     * @param destinationSupplier the live destination view; must yield at
     *                            least one destination while the source runs
     * @param framePeriodMillis   the constant interval between frames, in ms
     * @throws NullPointerException     for a null argument
     * @throws IllegalArgumentException for a non-positive frame period
     */
    public StreamSource(String flowId,
                        MjpegVideoStream video,
                        DataChannel channel,
                        Supplier<? extends Collection<Integer>> destinationSupplier,
                        long framePeriodMillis) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
        this.video = Objects.requireNonNull(video, "video");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.destinationSupplier = Objects.requireNonNull(destinationSupplier, "destinationSupplier");
        if (framePeriodMillis <= 0) {
            throw new IllegalArgumentException("frame period must be positive: " + framePeriodMillis);
        }
        this.framePeriodMillis = framePeriodMillis;
    }

    /**
     * Purpose: validate a fixed destination collection and wrap it in a
     * constant supplier.
     */
    private static Supplier<List<Integer>> fixedDestinations(Collection<Integer> destinationNodeIds) {
        Objects.requireNonNull(destinationNodeIds, "destinationNodeIds");
        if (destinationNodeIds.isEmpty()) {
            throw new IllegalArgumentException("at least one destination is required");
        }
        List<Integer> copy = List.copyOf(destinationNodeIds);
        return () -> copy;
    }

    /**
     * Purpose: start timer-driven sending at the configured frame rate. The
     * source stops itself at end of video or when {@link #stop()} is called.
     *
     * @throws IllegalStateException if already started
     */
    public synchronized void start() {
        if (scheduler != null) {
            throw new IllegalStateException("source already started");
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "stream-source-" + flowId);
            thread.setDaemon(false);
            return thread;
        });
        scheduler.scheduleAtFixedRate(
                this::sendNextFrameQuietly, 0L, framePeriodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Purpose: block until the source is done (end of video or
     * {@link #stop()}). For the CLI server mode, which otherwise would exit
     * immediately because the receiver threads are daemons.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public void awaitTermination() throws InterruptedException {
        doneLatch.await();
    }

    /**
     * Purpose: send the next frame to every destination once — the synchronous
     * building block of the timer-driven mode and of {@link #pumpAll()}.
     *
     * @return true if a frame was sent, false at end of video
     * @throws IOException if reading the video file fails
     */
    public boolean sendNextFrame() throws IOException {
        byte[] payload = video.nextFrame();
        if (payload == null) {
            return false;
        }

        DataPacketHeader header = new DataPacketHeader(flowId, nextSequenceNumber++, payload);
        byte[] datagram = DataPacketHeader.encode(header);
        for (int destinationNodeId : destinationSupplier.get()) {
            try {
                channel.sendDatagram(destinationNodeId, datagram);
            } catch (IllegalStateException linkDown) {
                System.out.printf("source: skipping destination %d for seq=%d: %s%n",
                        destinationNodeId, header.sequenceNumber(), linkDown.getMessage());
            }
        }
        framesSent++;
        return true;
    }

    /**
     * Purpose: synchronously send the whole video, frame by frame. Intended
     * for tests and in-JVM demos where no real time should pass.
     *
     * @return the number of frames sent
     * @throws IOException if reading the video file fails
     */
    public long pumpAll() throws IOException {
        long sent = 0;
        while (sendNextFrame()) {
            sent++;
        }
        return sent;
    }

    /**
     * Purpose: stop the timer-driven source. Idempotent.
     */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        doneLatch.countDown();
    }

    /** Purpose: frames sent so far. */
    public synchronized long framesSent() {
        return framesSent;
    }

    /**
     * Purpose: the timer callback — send one frame, and stop on end of video
     * or on a read failure, so a dead video never spins the scheduler.
     */
    private void sendNextFrameQuietly() {
        try {
            if (!sendNextFrame()) {
                stop();
            }
        } catch (IOException readFailure) {
            System.out.println("source: video read failed, stopping: " + readFailure.getMessage());
            stop();
        } catch (RuntimeException unexpected) {
            System.out.println("source: send failed, stopping: " + unexpected);
            stop();
        }
    }
}
