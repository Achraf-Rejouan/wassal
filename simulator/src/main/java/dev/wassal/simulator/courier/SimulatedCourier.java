package dev.wassal.simulator.courier;

import dev.wassal.simulator.roadgraph.RoadGraph;
import java.util.Random;
import java.util.UUID;

/**
 * One courier walking the road graph (FR-018).
 *
 * <p>Holds no {@link Random} of its own — every draw comes from the shared seeded stream passed in
 * by the tick loop. A per-courier RNG would still be deterministic in isolation but the
 * <em>interleaving</em> would not be, and NFR-008 requires byte-identical runs, not approximately
 * identical ones.
 */
public final class SimulatedCourier {

    private static final double MIN_SPEED_KMH = 15;
    private static final double MAX_SPEED_KMH = 40;

    private final UUID id;
    private int currentNode;
    private int previousNode = -1;
    private double metresIntoEdge;
    private int targetNode;
    private double speedKmh;
    private long idleUntilTick;

    public SimulatedCourier(UUID id, RoadGraph graph, Random random) {
        this.id = id;
        this.currentNode = graph.randomNode(random);
        this.targetNode = pickNeighbour(graph, random);
        this.speedKmh = MIN_SPEED_KMH + random.nextDouble() * (MAX_SPEED_KMH - MIN_SPEED_KMH);
    }

    public UUID id() {
        return id;
    }

    public double lat(RoadGraph graph) {
        return interpolate(graph.latOf(currentNode), graph.latOf(targetNode), graph);
    }

    public double lon(RoadGraph graph) {
        return interpolate(graph.lonOf(currentNode), graph.lonOf(targetNode), graph);
    }

    public double speedKmh() {
        return idleUntilTick > 0 ? 0 : speedKmh;
    }

    /** Advances one tick. Stops and idle periods are modelled, not merely mentioned. */
    public void step(RoadGraph graph, Random random, long tick, double tickSeconds) {
        if (tick < idleUntilTick) {
            return;
        }

        double metresThisTick = speedKmh / 3.6 * tickSeconds;
        metresIntoEdge += metresThisTick;

        double edgeLength = currentEdgeLength(graph);
        while (metresIntoEdge >= edgeLength) {
            metresIntoEdge -= edgeLength;
            previousNode = currentNode;
            currentNode = targetNode;
            targetNode = pickNeighbour(graph, random);
            edgeLength = currentEdgeLength(graph);

            // Realistic stops: a short pause at roughly one junction in twenty.
            if (random.nextDouble() < 0.05) {
                idleUntilTick = tick + 1 + random.nextInt(10);
                metresIntoEdge = 0;
                return;
            }
            // Speed varies between segments rather than being fixed for the whole run.
            if (random.nextDouble() < 0.2) {
                speedKmh = MIN_SPEED_KMH + random.nextDouble() * (MAX_SPEED_KMH - MIN_SPEED_KMH);
            }
        }
    }

    /**
     * Prefers not to double back, which is what makes the walk look like driving rather than like a
     * random walk. Reverses at a dead end — the graph has some, and a courier stuck at one would
     * silently stop generating load.
     */
    private int pickNeighbour(RoadGraph graph, Random random) {
        int[] options = graph.neighboursOf(currentNode);
        if (options.length == 0) {
            return currentNode;
        }
        if (options.length == 1) {
            return options[0];
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            int candidate = options[random.nextInt(options.length)];
            if (candidate != previousNode) {
                return candidate;
            }
        }
        return options[random.nextInt(options.length)];
    }

    private double currentEdgeLength(RoadGraph graph) {
        int[] options = graph.neighboursOf(currentNode);
        for (int i = 0; i < options.length; i++) {
            if (options[i] == targetNode) {
                return Math.max(1.0, graph.edgeLength(currentNode, i));
            }
        }
        return 50.0;
    }

    private double interpolate(double from, double to, RoadGraph graph) {
        double edgeLength = currentEdgeLength(graph);
        double fraction = Math.min(1.0, metresIntoEdge / edgeLength);
        return from + (to - from) * fraction;
    }
}
