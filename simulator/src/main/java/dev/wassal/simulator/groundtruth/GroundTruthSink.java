package dev.wassal.simulator.groundtruth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

/**
 * What the simulator <em>intended</em> to happen (FR-020) — the artifact that makes validation
 * non-circular.
 *
 * <p>Deliberately a JSONL file on a volume rather than a database table. A table would be more
 * convenient, and that is exactly the problem: writing ground truth into the same store the system
 * under test writes to creates an opportunity for the two to become entangled, and the whole value
 * of this file is that it cannot. Convenience is the wrong optimisation target for a proof
 * artifact.
 */
public class GroundTruthSink implements AutoCloseable {

    private final BufferedWriter writer;
    private final ObjectMapper json = new ObjectMapper();

    public GroundTruthSink(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        this.writer =
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
    }

    /** Appends one intent record. Synchronised: several courier threads may emit at once. */
    public synchronized void record(String type, Map<String, Object> fields) {
        try {
            var record = new java.util.LinkedHashMap<String, Object>();
            record.put("type", type);
            record.put("at", Instant.now().toString());
            record.putAll(fields);
            writer.write(json.writeValueAsString(record));
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write ground truth", e);
        }
    }

    @Override
    public void close() throws IOException {
        writer.flush();
        writer.close();
    }
}
