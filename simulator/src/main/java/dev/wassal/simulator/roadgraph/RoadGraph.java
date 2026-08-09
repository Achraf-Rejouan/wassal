package dev.wassal.simulator.roadgraph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The precomputed OSM road graph (A-01, built by {@code infra/tools/build-road-graph}).
 *
 * <p>Couriers random-walk this graph rather than being routed across it. The brief asked for
 * movement along real OSM geometry, which read literally implies a routing engine — a multi-day
 * dependency with its own container, for a component whose only job is generating load. Walking a
 * precomputed graph gives identical fidelity: couriers follow real streets, and true
 * origin-destination routing is something load generation never needed.
 *
 * <p>Arrays rather than maps: node ids were densified at build time specifically so this can be
 * indexed, and at 300 couriers stepping several times a second the difference is worth having.
 */
public final class RoadGraph {

    private final double[] lat;
    private final double[] lon;
    private final int[][] neighbours;
    private final double[][] edgeLengths;
    private final String source;

    private RoadGraph(
            double[] lat, double[] lon, int[][] neighbours, double[][] edgeLengths, String source) {
        this.lat = lat;
        this.lon = lon;
        this.neighbours = neighbours;
        this.edgeLengths = edgeLengths;
        this.source = source;
    }

    public static RoadGraph load(InputStream json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        int nodeCount = root.get("nodeCount").asInt();

        double[] lat = new double[nodeCount];
        double[] lon = new double[nodeCount];
        for (JsonNode node : root.get("nodes")) {
            int id = node.get("id").asInt();
            lat[id] = node.get("lat").asDouble();
            lon[id] = node.get("lon").asDouble();
        }

        List<List<int[]>> adjacency = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            adjacency.add(new ArrayList<>());
        }
        List<List<Double>> lengths = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            lengths.add(new ArrayList<>());
        }

        for (JsonNode edge : root.get("edges")) {
            int from = edge.get("from").asInt();
            int to = edge.get("to").asInt();
            double length = edge.get("lengthM").asDouble();
            adjacency.get(from).add(new int[] {to});
            lengths.get(from).add(length);
            adjacency.get(to).add(new int[] {from});
            lengths.get(to).add(length);
        }

        int[][] neighbours = new int[nodeCount][];
        double[][] edgeLengths = new double[nodeCount][];
        for (int i = 0; i < nodeCount; i++) {
            neighbours[i] = adjacency.get(i).stream().mapToInt(a -> a[0]).toArray();
            edgeLengths[i] = lengths.get(i).stream().mapToDouble(Double::doubleValue).toArray();
        }

        return new RoadGraph(lat, lon, neighbours, edgeLengths, root.get("source").asText());
    }

    public int nodeCount() {
        return lat.length;
    }

    public double latOf(int node) {
        return lat[node];
    }

    public double lonOf(int node) {
        return lon[node];
    }

    public int[] neighboursOf(int node) {
        return neighbours[node];
    }

    public double edgeLength(int node, int neighbourIndex) {
        return edgeLengths[node][neighbourIndex];
    }

    public String source() {
        return source;
    }

    /**
     * Picks a starting node. Takes the {@link Random} as an argument rather than owning one, so
     * every source of randomness in the simulator traces back to the single seeded stream that
     * NFR-008's reproducibility depends on.
     */
    public int randomNode(Random random) {
        return random.nextInt(lat.length);
    }
}
