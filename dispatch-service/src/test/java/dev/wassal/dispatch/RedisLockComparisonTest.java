package dev.wassal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;

/**
 * S2-12 — the evidence behind ADR-0004, which chose a Postgres conditional update over a Redis
 * distributed lock.
 *
 * <p>ADR-0004 argued the point; this measures it. The Redis variant here is deliberately confined
 * to the test source set and is never wired into a production path — the deliverable is a
 * comparison, not an alternative implementation.
 *
 * <p><strong>The crash window, stated precisely.</strong> This is what decided the ADR, and it is
 * easier to believe as a sequence than as prose:
 *
 * <pre>
 *   Redis lock                                  Postgres conditional update
 *   ---------------------------------------     -------------------------------------------
 *   1. SET courier:X lock NX PX 30000    OK      1. BEGIN
 *   2. &lt;&lt;&lt; PROCESS DIES HERE &gt;&gt;&gt;                  2. UPDATE couriers SET status='BUSY'
 *      courier X is locked, no assignment           WHERE id=X AND status='AVAILABLE'
 *      exists, and nothing in Postgres              -- 0 rows =&gt; lost, return
 *      records that a claim was attempted        3. INSERT assignment
 *   3. Lock expires after the TTL               4. &lt;&lt;&lt; PROCESS DIES HERE &gt;&gt;&gt;
 *      — but how long? Too short and a lock        Postgres rolls back. Courier is
 *      can expire mid-assignment, letting a         AVAILABLE again. No orphan state.
 *      second claim through. Too long and a
 *      crashed claim strands a courier.
 * </pre>
 *
 * <p>The lock's TTL has no correct value: it must exceed the longest possible assignment (or mutual
 * exclusion breaks) while staying short enough that a crash does not strand a courier for minutes.
 * The transactional variant has no such parameter, because the store that holds the data is the
 * store that arbitrates access to it. That is Kleppmann's fencing-token argument reduced to this
 * specific claim.
 *
 * <p>The benchmark below is secondary and, honestly, favours Redis on raw latency. Throughput was
 * never the reason for the decision, and publishing a number that flatters the rejected option is
 * the point of measuring rather than asserting.
 */
@Testcontainers
class RedisLockComparisonTest {

    private static final int COURIERS = 20;
    private static final int ATTEMPTS_PER_COURIER = 25; // 500 contended attempts

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("bench")
                    .withUsername("bench")
                    .withPassword("bench");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /** Mutual exclusion by conditional UPDATE — the shape production uses. */
    @Test
    @DisplayName("Postgres conditional update: exactly one claim per courier, no orphans")
    void postgresConditionalUpdate() throws Exception {
        try (Connection setup = connect()) {
            setup.createStatement()
                    .execute(
                            """
                            DROP TABLE IF EXISTS bench_couriers;
                            CREATE TABLE bench_couriers (
                                id uuid PRIMARY KEY, status text NOT NULL);
                            """);
        }

        List<UUID> couriers = seedCouriers();
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();

        long nanos =
                race(
                        couriers,
                        courierId -> {
                            try (Connection connection = connect();
                                    PreparedStatement statement =
                                            connection.prepareStatement(
                                                    "UPDATE bench_couriers SET status = 'BUSY'"
                                                            + " WHERE id = ? AND status = 'AVAILABLE'")) {
                                statement.setObject(1, courierId);
                                if (statement.executeUpdate() == 1) {
                                    won.incrementAndGet();
                                } else {
                                    lost.incrementAndGet();
                                }
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });

        assertThat(won.get()).as("exactly one claim per courier may succeed").isEqualTo(COURIERS);
        assertThat(lost.get()).isEqualTo(COURIERS * ATTEMPTS_PER_COURIER - COURIERS);
        report("postgres-conditional-update", nanos, won.get(), lost.get());
    }

    /** Mutual exclusion by Redis lock — correct on the happy path, and that is the trap. */
    @Test
    @DisplayName("Redis SET NX lock: also exactly one claim per courier — on the happy path")
    void redisSetNxLock() throws Exception {
        List<UUID> couriers = seedCourierIds();
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();

        try (Jedis jedis = jedis()) {
            jedis.flushAll();
        }

        long nanos =
                race(
                        couriers,
                        courierId -> {
                            try (Jedis jedis = jedis()) {
                                String token = UUID.randomUUID().toString();
                                String ok =
                                        jedis.set(
                                                "lock:courier:" + courierId,
                                                token,
                                                redis.clients.jedis.params.SetParams.setParams()
                                                        .nx()
                                                        .px(30_000));
                                if ("OK".equals(ok)) {
                                    won.incrementAndGet();
                                } else {
                                    lost.incrementAndGet();
                                }
                            }
                        });

        assertThat(won.get()).isEqualTo(COURIERS);
        report("redis-set-nx-lock", nanos, won.get(), lost.get());
    }

    /**
     * The failure the benchmark cannot show and the ADR turns on: a crash between acquiring the
     * lock and writing the assignment.
     */
    @Test
    @DisplayName("the crash window: Redis strands a courier, Postgres does not")
    void crashBetweenClaimAndAssignment() throws Exception {
        UUID courierId = UUID.randomUUID();

        // --- Redis: acquire the lock, then "die" before the assignment is written.
        try (Jedis jedis = jedis()) {
            jedis.flushAll();
            jedis.set(
                    "lock:courier:" + courierId,
                    "holder",
                    redis.clients.jedis.params.SetParams.setParams().nx().px(30_000));
            // Process dies here. Nothing else runs.

            String survivingLock = jedis.get("lock:courier:" + courierId);
            assertThat(survivingLock)
                    .as(
                            "the lock outlives the process that took it: courier is claimed, no"
                                    + " assignment exists, and only a TTL will release them")
                    .isEqualTo("holder");
            assertThat(jedis.pttl("lock:courier:" + courierId))
                    .as("the courier is stranded for whatever the TTL happens to be")
                    .isGreaterThan(0);
        }

        // --- Postgres: claim inside a transaction, then "die" before commit.
        try (Connection setup = connect()) {
            setup.createStatement()
                    .execute(
                            """
                            DROP TABLE IF EXISTS crash_couriers;
                            CREATE TABLE crash_couriers (id uuid PRIMARY KEY, status text NOT NULL);
                            """);
            try (PreparedStatement insert =
                    setup.prepareStatement("INSERT INTO crash_couriers VALUES (?, 'AVAILABLE')")) {
                insert.setObject(1, courierId);
                insert.executeUpdate();
            }
        }

        try (Connection dying = connect()) {
            dying.setAutoCommit(false);
            try (PreparedStatement claim =
                    dying.prepareStatement(
                            "UPDATE crash_couriers SET status='BUSY'"
                                    + " WHERE id = ? AND status = 'AVAILABLE'")) {
                claim.setObject(1, courierId);
                assertThat(claim.executeUpdate()).isOne();
            }
            // Process dies here — no commit. Closing without committing is exactly what a killed
            // JVM does to an open transaction.
        }

        try (Connection observer = connect();
                PreparedStatement query =
                        observer.prepareStatement(
                                "SELECT status FROM crash_couriers WHERE id = ?")) {
            query.setObject(1, courierId);
            try (ResultSet rs = query.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1))
                        .as(
                                "the courier is AVAILABLE again with no cleanup, no TTL and no"
                                        + " compensating job — the database undid the partial claim")
                        .isEqualTo("AVAILABLE");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private long race(List<UUID> couriers, java.util.function.Consumer<UUID> attempt)
            throws Exception {
        int total = couriers.size() * ATTEMPTS_PER_COURIER;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (UUID courierId : couriers) {
            for (int i = 0; i < ATTEMPTS_PER_COURIER; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    startGun.await();
                                    attempt.accept(courierId);
                                    return null;
                                }));
            }
        }

        long start = System.nanoTime();
        startGun.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        long elapsed = System.nanoTime() - start;
        pool.shutdown();
        assertThat(futures).hasSize(total);
        return elapsed;
    }

    private List<UUID> seedCouriers() throws Exception {
        List<UUID> ids = seedCourierIds();
        try (Connection connection = connect();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO bench_couriers VALUES (?, 'AVAILABLE')")) {
            for (UUID id : ids) {
                insert.setObject(1, id);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        return ids;
    }

    private List<UUID> seedCourierIds() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < COURIERS; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Jedis jedis() {
        return new Jedis(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    private static void report(String variant, long nanos, int won, int lost) {
        System.out.printf(
                "%n[S2-12] %-28s %5d attempts  %6.1f ms total  %6.3f ms/attempt  won=%d lost=%d%n",
                variant,
                won + lost,
                nanos / 1_000_000.0,
                nanos / 1_000_000.0 / (won + lost),
                won,
                lost);
    }
}
