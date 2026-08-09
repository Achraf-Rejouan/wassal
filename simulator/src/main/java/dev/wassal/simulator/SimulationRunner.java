package dev.wassal.simulator;

import dev.wassal.simulator.arrivals.PoissonArrivals;
import dev.wassal.simulator.courier.SimulatedCourier;
import dev.wassal.simulator.groundtruth.GroundTruthSink;
import dev.wassal.simulator.profile.SimulationProfile;
import dev.wassal.simulator.roadgraph.RoadGraph;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The tick loop (FR-018, FR-019, FR-021).
 *
 * <p><strong>One RNG, one thread of decision-making.</strong> Every draw — movement, offer
 * responses, arrivals — comes from a single seeded {@link Random} consumed in a fixed order. That
 * is what makes a run byte-reproducible (NFR-008), and it is why the loop is single threaded even
 * though the HTTP calls it makes are not.
 */
@Component
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);
    private static final double TICK_SECONDS = 1.0;

    private final DispatchClient client;
    private final String profileName;
    private final long seed;
    private final int runSeconds;
    private final boolean enabled;
    private final Path groundTruthDir;
    private final String graphResource;

    public SimulationRunner(
            DispatchClient client,
            @Value("${wassal.sim.profile:standard}") String profileName,
            @Value("${wassal.sim.seed:42}") long seed,
            @Value("${wassal.sim.run-seconds:0}") int runSeconds,
            @Value("${wassal.sim.enabled:true}") boolean enabled,
            @Value("${wassal.sim.ground-truth-dir:/data/ground-truth}") String groundTruthDir,
            @Value("${wassal.sim.graph:/tunis-road-graph.json}") String graphResource) {
        this.client = client;
        this.profileName = profileName;
        this.seed = seed;
        this.runSeconds = runSeconds;
        this.enabled = enabled;
        this.groundTruthDir = Path.of(groundTruthDir);
        this.graphResource = graphResource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() throws Exception {
        if (!enabled) {
            log.info("Simulator disabled");
            return;
        }
        SimulationProfile profile = SimulationProfile.byName(profileName, seed);
        RoadGraph graph;
        try (InputStream in = getClass().getResourceAsStream(graphResource)) {
            if (in == null) {
                throw new IllegalStateException("Road graph not found at " + graphResource);
            }
            graph = RoadGraph.load(in);
        }

        log.info(
                "Starting profile={} seed={} couriers={} graph={} nodes={}",
                profile.name(),
                profile.seed(),
                profile.courierCount(),
                graph.source(),
                graph.nodeCount());

        // Seeded here and nowhere else. Every source of randomness downstream draws from this.
        Random random = new Random(profile.seed());

        // I/O only. Deliberately separate from the decision loop: every RNG draw stays on the
        // single tick thread, so a run remains byte-reproducible while HTTP stops being the
        // bottleneck.
        java.util.concurrent.ExecutorService ioPool =
                java.util.concurrent.Executors.newFixedThreadPool(24);

        try (GroundTruthSink truth =
                new GroundTruthSink(
                        groundTruthDir.resolve(
                                "run-%s-seed%d.jsonl".formatted(profile.name(), profile.seed())))) {

            truth.record(
                    "run_started",
                    Map.of(
                            "profile", profile.name(),
                            "seed", profile.seed(),
                            "courierCount", profile.courierCount(),
                            "graphSource", graph.source()));

            List<SimulatedCourier> couriers = new ArrayList<>();
            for (int i = 0; i < profile.courierCount(); i++) {
                SimulatedCourier courier =
                        new SimulatedCourier(deterministicId(seed, i), graph, random);
                couriers.add(courier);
                client.goAvailable(courier.id(), courier.lat(graph), courier.lon(graph));
                truth.record("courier_available", Map.of("courierId", courier.id().toString()));
            }

            PoissonArrivals arrivals =
                    new PoissonArrivals(profile.ordersPerMinute(), profile.peakMultiplier());

            Instant deadline =
                    runSeconds > 0 ? Instant.now().plus(Duration.ofSeconds(runSeconds)) : null;
            long tick = 0;

            while (deadline == null || Instant.now().isBefore(deadline)) {
                tick++;

                // Offers FIRST. They carry a 15s deadline; positions do not carry one at all,
                // so anything that delays the response costs accepts. The first version reported
                // 300 positions before looking at the queue and almost every offer expired
                // unanswered (see docs/bug-log.md).
                client.respondToOffers(random, profile, truth);

                for (SimulatedCourier courier : couriers) {
                    courier.step(graph, random, tick, TICK_SECONDS);
                }

                if (tick % profile.positionIntervalSeconds() == 0) {
                    // Positions are COMPUTED here, in order, from the single seeded stream —
                    // determinism is untouched. Only the HTTP calls are parallel, and I/O
                    // carries no RNG draws (NFR-008).
                    List<Runnable> sends = new ArrayList<>(couriers.size());
                    for (SimulatedCourier courier : couriers) {
                        double lat = courier.lat(graph);
                        double lon = courier.lon(graph);
                        double speed = courier.speedKmh();
                        UUID id = courier.id();
                        sends.add(() -> client.reportPosition(id, lat, lon, speed));
                        truth.record(
                                "position",
                                Map.of("courierId", id.toString(), "lat", lat, "lon", lon));
                    }
                    sends.forEach(ioPool::execute);
                }

                int newOrders = arrivals.arrivalsInTick(random, TICK_SECONDS, inPeak(tick));
                for (int i = 0; i < newOrders; i++) {
                    SimulatedCourier near = couriers.get(random.nextInt(couriers.size()));
                    UUID orderId =
                            client.createOrder(
                                    jitter(near.lat(graph), random, profile.radiusMetres()),
                                    jitter(near.lon(graph), random, profile.radiusMetres()));
                    if (orderId != null) {
                        truth.record("order_created", Map.of("orderId", orderId.toString()));
                    }
                }

                Thread.sleep((long) (TICK_SECONDS * 1000));
            }

            truth.record("run_finished", Map.of("ticks", tick));
            log.info("Simulation finished after {} ticks", tick);
        } finally {
            ioPool.shutdownNow();
        }
    }

    /** Peak window: roughly a fifth of the run, so a long run exercises both regimes. */
    private static boolean inPeak(long tick) {
        return (tick / 60) % 5 == 3;
    }

    /**
     * Courier ids are derived from the seed rather than random, so the SAME run produces the same
     * identities. Without this, two runs of one seed would be behaviourally identical but not
     * comparable record-for-record, which is what FR-020's ground truth needs.
     */
    private static UUID deterministicId(long seed, int index) {
        return UUID.nameUUIDFromBytes(("courier-%d-%d".formatted(seed, index)).getBytes());
    }

    private static double jitter(double value, Random random, double radiusMetres) {
        double degrees = radiusMetres / 111_320.0;
        return value + (random.nextDouble() - 0.5) * 2 * degrees;
    }
}
