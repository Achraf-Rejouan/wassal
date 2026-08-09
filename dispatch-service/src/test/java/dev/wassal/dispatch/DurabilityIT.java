package dev.wassal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wassal.dispatch.domain.model.ClaimResult;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.infra.persistence.AtomicClaimExecutor;
import dev.wassal.dispatch.infra.persistence.CancellationSaga;
import dev.wassal.dispatch.infra.persistence.OfferRepository;
import dev.wassal.dispatch.infra.sweeper.OfferExpirySweeper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Sprint 3's proof: durable expiry, the accept-vs-expire race, and compensation (S3-09, S3-10,
 * S3-11).
 *
 * <p>The sweeper is driven manually here rather than by its scheduler. That is deliberate: a test
 * that waits for a 250 ms tick is a test whose timing is decided by a thread pool, and flaky
 * durability tests get disabled, which silently removes the entire failure-correctness claim
 * (threat E-03). Driving it explicitly makes the ordering of accept and expiry something the test
 * controls rather than hopes for.
 */
@SpringBootTest
@Testcontainers
class DurabilityIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("postgis/postgis:16-3.4")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("wassal")
                    .withUsername("wassal")
                    .withPassword("wassal");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("wassal.outbox.poll-interval-ms", () -> "3600000");
        // Scheduler off: this suite drives the sweeper by hand so timing is deterministic.
        registry.add("wassal.sweeper.interval-ms", () -> "3600000");
    }

    @Autowired OfferExpirySweeper sweeper;
    @Autowired OfferRepository offers;
    @Autowired AtomicClaimExecutor claim;
    @Autowired CancellationSaga saga;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update(
                "TRUNCATE dispatch.sagas, dispatch.assignments, dispatch.offers,"
                        + " dispatch.dispatch_outbox, dispatch.order_pickups,"
                        + " dispatch.processed_messages, dispatch.couriers CASCADE",
                new MapSqlParameterSource());
    }

    // ---------------------------------------------------------------- INV-4 / FR-011

    @Test
    @DisplayName("INV-4: an overdue offer expires and does not sit in an intermediate state")
    void overdueOffersExpire() {
        CourierId courier = seedCourier();
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMillis(50), 1);

        sleep(120);
        sweeper.sweep();

        assertThat(offerStatus(offerId)).isEqualTo("EXPIRED");
        assertThat(sweeper.currentLagMillis())
                .as("lag must stay inside NFR-004's ±1s budget")
                .isLessThan(1_000);
    }

    @Test
    @DisplayName("FR-011: expiry survives a restart spanning the deadline — the headline claim")
    void expirySurvivesProcessDeath() {
        CourierId courier = seedCourier();
        // A 15s offer, as in production.
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofSeconds(15), 1);
        Instant deadline = offers.deadlineOf(offerId);

        // Simulate a process that dies at t=7s and comes back at t=12s: the sweeper simply never
        // runs during that window. Nothing in the JVM is holding the deadline — it is a column,
        // which is the entire point of ADR-0005. An in-process timer would have died here and
        // the order would hang forever.
        assertThat(offerStatus(offerId)).isEqualTo("OFFERED");

        // Rather than sleeping 15s, move the offer BACK in time — both timestamps together, so
        // it reads as an offer made 16s ago whose deadline passed 1s ago. Shifting only
        // expires_at would put the deadline before the offer and chk_deadline_after_offer would
        // (correctly) refuse the row; the constraint caught exactly that on the first attempt.
        //
        // What is under test is that the deadline lives in the row and is evaluated by whoever
        // runs next, not that a thread waited.
        jdbc.update(
                """
                UPDATE dispatch.offers
                   SET offered_at = now() - interval '16 seconds',
                       expires_at = now() - interval '1 second'
                 WHERE id = :id
                """,
                new MapSqlParameterSource("id", offerId.value()));

        // "Restart": the very first sweep after recovery finds it.
        sweeper.sweep();

        assertThat(offerStatus(offerId))
                .as("a deadline in a column outlives the process that created it")
                .isEqualTo("EXPIRED");
        assertThat(deadline).isNotNull();
    }

    @Test
    @DisplayName("multiple sweeper passes expire each overdue offer exactly once")
    void sweepIsIdempotentAcrossPasses() {
        CourierId courier = seedCourier();
        List<OfferId> offerIds =
                IntStream.range(0, 20)
                        .mapToObj(
                                i ->
                                        offers.create(
                                                OrderId.newId(), courier, Duration.ofMillis(20), 1))
                        .toList();
        sleep(80);

        sweeper.sweep();
        sweeper.sweep(); // a second pass must find nothing left to do

        Integer expired =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.offers WHERE status = 'EXPIRED'",
                        new MapSqlParameterSource(),
                        Integer.class);
        Integer expiryEvents =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.dispatch_outbox"
                                + " WHERE event_type = 'OfferExpired'",
                        new MapSqlParameterSource(),
                        Integer.class);

        assertThat(expired).isEqualTo(offerIds.size());
        assertThat(expiryEvents)
                .as("one expiry event per offer — a second sweep must not re-emit")
                .isEqualTo(offerIds.size());
    }

    // ---------------------------------------------------------------- FR-012, both orderings

    @Test
    @DisplayName("FR-012: accept BEFORE the sweeper wins, and the sweeper then finds nothing")
    void acceptBeforeSweeperWins() {
        CourierId courier = seedCourier();
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofSeconds(30), 1);

        ClaimResult result = claim.acceptOffer(offerId, courier);
        sweeper.sweep();

        assertThat(result).isInstanceOf(ClaimResult.Assigned.class);
        assertThat(offerStatus(offerId))
                .as("the sweeper's later pass must affect zero rows, not overwrite the accept")
                .isEqualTo("ACCEPTED");
        assertThat(countAssignments()).isOne();
    }

    @Test
    @DisplayName(
            "FR-012: sweeper BEFORE accept wins, and the late accept gets 410 with no assignment")
    void sweeperBeforeAcceptWins() {
        CourierId courier = seedCourier();
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMillis(30), 1);

        sleep(80);
        sweeper.sweep();
        ClaimResult result = claim.acceptOffer(offerId, courier);

        assertThat(offerStatus(offerId)).isEqualTo("EXPIRED");
        assertThat(result)
                .as("expiry is authoritative: a late accept loses even arriving first at the DB")
                .isInstanceOf(ClaimResult.OfferExpired.class);
        assertThat(countAssignments())
                .as("no assignment may exist behind an expired offer")
                .isZero();
        assertThat(courierStatus(courier))
                .as("and the courier must not have been marked busy")
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("FR-012: accept and sweep racing concurrently converge on exactly one outcome")
    void acceptAndSweepRaceConverges() throws Exception {
        // Repeated, because a race that resolves correctly once may have done so by luck.
        for (int round = 0; round < 25; round++) {
            reset();
            CourierId courier = seedCourier();
            // A deadline right at the edge, so accept and sweep genuinely collide.
            OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMillis(40), 1);

            CountDownLatch startGun = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Future<Object> accepting =
                    pool.submit(
                            () -> {
                                startGun.await();
                                try {
                                    return claim.acceptOffer(offerId, courier);
                                } catch (AtomicClaimExecutor.ClaimLostException e) {
                                    return e.result();
                                }
                            });
            Future<?> sweeping =
                    pool.submit(
                            () -> {
                                startGun.await();
                                sweeper.sweep();
                                return null;
                            });

            sleep(40); // let the deadline pass so both paths are live
            startGun.countDown();
            Object outcome = accepting.get(30, TimeUnit.SECONDS);
            sweeping.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            String status = offerStatus(offerId);
            int assignments = countAssignments();

            // Exactly one of two coherent worlds, never a mixture. This is the whole of FR-012:
            // both paths funnel through one conditional transition, so the loser sees zero rows.
            boolean acceptedWorld =
                    "ACCEPTED".equals(status)
                            && assignments == 1
                            && outcome instanceof ClaimResult.Assigned;
            boolean expiredWorld =
                    "EXPIRED".equals(status)
                            && assignments == 0
                            && outcome instanceof ClaimResult.OfferExpired;

            assertThat(acceptedWorld || expiredWorld)
                    .as(
                            "round %d converged on an incoherent state: status=%s assignments=%d"
                                    + " outcome=%s",
                            round, status, assignments, outcome.getClass().getSimpleName())
                    .isTrue();
        }
    }

    // ---------------------------------------------------------------- INV-6

    @Test
    @DisplayName("INV-6: cancellation replayed 100 times releases the courier exactly once")
    void compensationReleasesExactlyOnce() throws Exception {
        CourierId courier = seedCourier();
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMinutes(10), 1);
        ClaimResult assigned = claim.acceptOffer(offerId, courier);
        assertThat(assigned).isInstanceOf(ClaimResult.Assigned.class);
        UUID assignmentId = ((ClaimResult.Assigned) assigned).assignmentId().value();

        // The SAME trigger id every time — this is a retry of one cancellation, not 100 of them.
        UUID triggerEventId = UUID.randomUUID();
        List<Object> results =
                race(
                        100,
                        i ->
                                saga.cancel(
                                        assignmentId,
                                        courier,
                                        triggerEventId,
                                        "VEHICLE_BREAKDOWN"));

        Integer releaseCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.assignments"
                                + " WHERE id = :id AND released_at IS NOT NULL",
                        new MapSqlParameterSource("id", assignmentId),
                        Integer.class);

        assertThat(releaseCount).isOne();
        assertThat(courierStatus(courier))
                .as("the courier returns to the pool")
                .isEqualTo("AVAILABLE");
        assertThat(results.stream().filter(r -> r instanceof CancellationSaga.Result.Compensated))
                .as("exactly one replay may perform the compensation")
                .hasSize(1);

        Integer sagaRows =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.sagas",
                        new MapSqlParameterSource(),
                        Integer.class);
        assertThat(sagaRows).as("a duplicated trigger must not start a second saga").isOne();
    }

    @Test
    @DisplayName("a crashed saga resumes from its recorded step rather than restarting")
    void sagaResumesFromRecordedStep() {
        CourierId courier = seedCourier();
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMinutes(10), 1);
        ClaimResult assigned = claim.acceptOffer(offerId, courier);
        UUID assignmentId = ((ClaimResult.Assigned) assigned).assignmentId().value();
        UUID orderId = ((ClaimResult.Assigned) assigned).orderId().value();

        // Simulate a crash after step 1 (assignment cancelled) but before the courier release:
        // the saga row exists, progress is recorded, and the work is half done.
        jdbc.update(
                "INSERT INTO dispatch.sagas (saga_type, aggregate_id, trigger_event_id,"
                        + " current_step) VALUES ('CANCELLATION', :orderId, :trigger, 1)",
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("trigger", UUID.randomUUID()));
        jdbc.update(
                "UPDATE dispatch.assignments SET status = 'CANCELLED', cancelled_at = now()"
                        + " WHERE id = :id",
                new MapSqlParameterSource("id", assignmentId));

        assertThat(courierStatus(courier))
                .as("precondition: the courier is still held by the half-finished saga")
                .isEqualTo("BUSY");

        int resumed = saga.resumeInFlight();

        assertThat(resumed).isOne();
        assertThat(courierStatus(courier))
                .as("resumption completed the release the crash interrupted")
                .isEqualTo("AVAILABLE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status::text FROM dispatch.sagas LIMIT 1",
                                new MapSqlParameterSource(),
                                String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("cancelling an already-cancelled assignment is refused, not compensated twice")
    void cancellingTerminalAssignmentIsRefused() {
        CourierId courier = seedCourier();
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMinutes(10), 1);
        UUID assignmentId =
                ((ClaimResult.Assigned) claim.acceptOffer(offerId, courier)).assignmentId().value();

        saga.cancel(assignmentId, courier, UUID.randomUUID(), "first");
        var second = saga.cancel(assignmentId, courier, UUID.randomUUID(), "second");

        assertThat(second).isInstanceOf(CancellationSaga.Result.AlreadyTerminal.class);
        Integer releases =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.assignments"
                                + " WHERE id = :id AND released_at IS NOT NULL",
                        new MapSqlParameterSource("id", assignmentId),
                        Integer.class);
        assertThat(releases).isOne();
    }

    // ---------------------------------------------------------------- helpers

    private <T> List<T> race(int n, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 32));
        CountDownLatch startGun = new CountDownLatch(1);
        List<Callable<T>> callables =
                IntStream.range(0, n)
                        .<Callable<T>>mapToObj(
                                i ->
                                        () -> {
                                            startGun.await();
                                            return task.apply(i);
                                        })
                        .toList();
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> callable : callables) {
            futures.add(pool.submit(callable));
        }
        startGun.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private CourierId seedCourier() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO dispatch.couriers (id, display_name, status, last_position,
                                               last_position_at)
                VALUES (:id, 'courier', 'AVAILABLE',
                        ST_SetSRID(ST_MakePoint(10.18, 36.80), 4326)::geography, now())
                """,
                new MapSqlParameterSource("id", id));
        return CourierId.of(id);
    }

    private String offerStatus(OfferId offerId) {
        return jdbc.queryForObject(
                "SELECT status::text FROM dispatch.offers WHERE id = :id",
                new MapSqlParameterSource("id", offerId.value()),
                String.class);
    }

    private String courierStatus(CourierId courierId) {
        return jdbc.queryForObject(
                "SELECT status::text FROM dispatch.couriers WHERE id = :id",
                new MapSqlParameterSource("id", courierId.value()),
                String.class);
    }

    private int countAssignments() {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.assignments WHERE status = 'ACTIVE'",
                        new MapSqlParameterSource(),
                        Integer.class);
        return n == null ? 0 : n;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
