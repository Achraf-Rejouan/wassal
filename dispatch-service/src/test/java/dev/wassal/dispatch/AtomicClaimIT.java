package dev.wassal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wassal.dispatch.domain.model.ClaimResult;
import dev.wassal.dispatch.domain.model.CourierId;
import dev.wassal.dispatch.domain.model.OfferId;
import dev.wassal.dispatch.domain.model.OrderId;
import dev.wassal.dispatch.infra.persistence.AtomicClaimExecutor;
import dev.wassal.dispatch.infra.persistence.InvariantCounters;
import dev.wassal.dispatch.infra.persistence.OfferRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The concurrency proof for INV-1, INV-2 and INV-3 (FR-008, S2-09).
 *
 * <p>Against a real PostGIS container — mocking here would be worthless, because what is under test
 * is precisely whether the <em>database</em> arbitrates the race.
 *
 * <p><strong>Every contended test asserts that contention actually occurred.</strong> A concurrency
 * test that passes because the race never happened is not a passing test, it is an unexecuted one,
 * and it is the single easiest way to ship a false proof. That is why {@code failedClaims > 0} is
 * an assertion here and not an observation.
 */
@SpringBootTest
@Testcontainers
class AtomicClaimIT {

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
        // The claim path opens a transaction per attempt; the pool must not be the bottleneck
        // under test, or this measures HikariCP rather than the claim.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
    }

    @Autowired AtomicClaimExecutor claim;
    @Autowired OfferRepository offers;
    @Autowired InvariantCounters counters;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update(
                "TRUNCATE dispatch.assignments, dispatch.offers, dispatch.dispatch_outbox,"
                        + " dispatch.processed_messages, dispatch.couriers CASCADE",
                new MapSqlParameterSource());
    }

    // ---------------------------------------------------------------- INV-1

    @Test
    @DisplayName("INV-1: 5000 concurrent accepts against 50 couriers never double-assign")
    void inv1NoDoubleAssignmentUnderContention() throws Exception {
        int couriers = 50;
        int attemptsPerCourier = 100; // 5,000 attempts total
        double inv1Before = counters.violationCount(InvariantCounters.Invariant.INV_1);
        List<CourierId> pool = seedCouriers(couriers);

        // Every courier gets `attemptsPerCourier` DISTINCT offers for DISTINCT orders, all live
        // simultaneously. That is what manufactures the race: at 50 orders/min it would occur
        // roughly never, so the harness has to create it deliberately.
        record Attempt(OfferId offerId, CourierId courierId) {}
        List<Attempt> attempts = new ArrayList<>();
        for (CourierId courier : pool) {
            for (int i = 0; i < attemptsPerCourier; i++) {
                OrderId orderId = OrderId.newId();
                attempts.add(
                        new Attempt(
                                offers.create(orderId, courier, Duration.ofMinutes(10), 1),
                                courier));
            }
        }
        assertThat(attempts).hasSize(couriers * attemptsPerCourier);

        var outcomes =
                raceAll(
                        attempts.size(),
                        i ->
                                claim.acceptOffer(
                                        attempts.get(i).offerId(), attempts.get(i).courierId()));

        long assigned = outcomes.stream().filter(r -> r instanceof ClaimResult.Assigned).count();
        long lost =
                outcomes.stream().filter(r -> r instanceof ClaimResult.CourierUnavailable).count();

        // The invariant itself, read from the database rather than inferred from responses.
        Integer couriersWithTwo =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM (
                            SELECT courier_id FROM dispatch.assignments
                             WHERE status = 'ACTIVE'
                             GROUP BY courier_id HAVING count(*) > 1) t
                        """,
                        new MapSqlParameterSource(),
                        Integer.class);

        assertThat(couriersWithTwo)
                .as("INV-1: no courier may hold two active assignments")
                .isZero();
        assertThat(assigned)
                .as("at most one assignment per courier can succeed")
                .isLessThanOrEqualTo(couriers);
        // Relative to a baseline: the counters are a singleton shared across this class, and
        // countersCanFire() deliberately injects into every one of them. Asserting an absolute
        // zero would make these tests order-dependent — which is its own kind of false proof.
        assertThat(counters.violationCount(InvariantCounters.Invariant.INV_1) - inv1Before)
                .as("INV-1 must not be violated at runtime")
                .isZero();

        // THE assertion that makes the rest meaningful. Without it, a run in which every attempt
        // happened to be serialised would report a triumphant pass having proven nothing.
        assertThat(lost)
                .as(
                        "contention must actually have occurred — zero lost claims means the race"
                                + " never happened and this test proved nothing")
                .isGreaterThan(0);

        // No attempt may hang. A timeout is not an acceptable answer to a lost race; the loser
        // needs a definite, immediate response or it will retry into a double-assignment.
        assertThat(outcomes).hasSize(attempts.size());
        assertThat(outcomes).allSatisfy(o -> assertThat(o).isNotNull());
    }

    // ---------------------------------------------------------------- INV-2

    @Test
    @DisplayName("INV-2: many couriers racing for one order produce exactly one assignment")
    void inv2SingleAssignmentPerOrder() throws Exception {
        int couriers = 40;
        double inv2Before = counters.violationCount(InvariantCounters.Invariant.INV_2);
        List<CourierId> pool = seedCouriers(couriers);
        OrderId contestedOrder = OrderId.newId();

        // One order, offered to every courier at once — the order-side race.
        List<OfferId> offerIds =
                IntStream.range(0, couriers)
                        .mapToObj(
                                i ->
                                        offers.create(
                                                contestedOrder,
                                                pool.get(i),
                                                Duration.ofMinutes(10),
                                                i + 1))
                        .toList();

        var outcomes = raceAll(couriers, i -> claim.acceptOffer(offerIds.get(i), pool.get(i)));

        Integer activeForOrder =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.assignments"
                                + " WHERE order_id = :orderId AND status = 'ACTIVE'",
                        new MapSqlParameterSource("orderId", contestedOrder.value()),
                        Integer.class);

        assertThat(activeForOrder).as("INV-2: an order has at most one active assignment").isOne();
        assertThat(outcomes.stream().filter(r -> r instanceof ClaimResult.Assigned).count())
                .isEqualTo(1);
        // The order-side race is an ORDINARY outcome, not a violation: 39 couriers legitimately
        // lost to the 40th. The constraint separated them and the counter must stay quiet, or a
        // healthy stress run would look like an incident.
        assertThat(counters.violationCount(InvariantCounters.Invariant.INV_2) - inv2Before)
                .as("losing the order race is expected traffic, not an invariant violation")
                .isZero();
    }

    // ---------------------------------------------------------------- INV-3

    @Test
    @DisplayName("INV-3: the same accept replayed 500 times in parallel yields one assignment")
    void inv3AcceptIsIdempotent() throws Exception {
        double inv3Before = counters.violationCount(InvariantCounters.Invariant.INV_3);
        CourierId courier = seedCouriers(1).get(0);
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMinutes(10), 1);

        // Replayed in PARALLEL, not in a loop. Duplicates arrive concurrently in reality, and a
        // sequential replay passes against implementations that are not actually idempotent.
        var outcomes = raceAll(500, i -> claim.acceptOffer(offerId, courier));

        Integer assignmentsForOffer =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.assignments WHERE offer_id = :offerId",
                        new MapSqlParameterSource("offerId", offerId.value()),
                        Integer.class);

        assertThat(assignmentsForOffer)
                .as("INV-3: one offer produces exactly one assignment")
                .isOne();

        long created = outcomes.stream().filter(r -> r instanceof ClaimResult.Assigned).count();
        long replayed =
                outcomes.stream().filter(r -> r instanceof ClaimResult.AlreadyAssigned).count();

        assertThat(created).isEqualTo(1);
        assertThat(created + replayed)
                .as("every replay must be answered with the original assignment, not an error")
                .isEqualTo(500);
        assertThat(counters.violationCount(InvariantCounters.Invariant.INV_3) - inv3Before)
                .as("INV-3 must not be violated at runtime")
                .isZero();
    }

    // ---------------------------------------------------------------- FR-012

    @Test
    @DisplayName(
            "FR-012: an accept after the deadline is refused even with the offer still OFFERED")
    void expiredOfferCannotBeAccepted() {
        CourierId courier = seedCouriers(1).get(0);
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMillis(1), 1);

        // The sweeper does not exist until Sprint 3, so the offer is still OFFERED. The accept
        // must fail on the DEADLINE alone — proving expires_at is a predicate in the claim and
        // not merely a trigger for a background job.
        await(50);

        ClaimResult result = claim.acceptOffer(offerId, courier);

        assertThat(result).isInstanceOf(ClaimResult.OfferExpired.class);
        assertThat(countAssignments()).isZero();
    }

    @Test
    @DisplayName("authorization is inside the claim: another courier cannot accept the offer")
    void otherCourierCannotAccept() {
        List<CourierId> pool = seedCouriers(2);
        OfferId offerId = offers.create(OrderId.newId(), pool.get(0), Duration.ofMinutes(10), 1);

        ClaimResult result = claim.acceptOffer(offerId, pool.get(1));

        assertThat(result).isInstanceOf(ClaimResult.NotOfferRecipient.class);
        assertThat(countAssignments()).isZero();
        assertThat(courierStatus(pool.get(1)))
                .as("a rejected claim must not have marked the impostor busy")
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("a lost courier claim rolls back the offer — no ACCEPTED offer without assignment")
    void lostCourierClaimRollsBackTheOffer() {
        CourierId courier = seedCouriers(1).get(0);
        // Force the courier BUSY so step 2 of the claim fails after step 1 has succeeded.
        jdbc.update(
                "UPDATE dispatch.couriers SET status = 'BUSY' WHERE id = :id",
                new MapSqlParameterSource("id", courier.value()));
        OfferId offerId = offers.create(OrderId.newId(), courier, Duration.ofMinutes(10), 1);

        ClaimResult result;
        try {
            result = claim.acceptOffer(offerId, courier);
        } catch (AtomicClaimExecutor.ClaimLostException e) {
            result = e.result();
        }

        assertThat(result).isInstanceOf(ClaimResult.CourierUnavailable.class);
        assertThat(countAssignments()).isZero();

        String offerStatus =
                jdbc.queryForObject(
                        "SELECT status::text FROM dispatch.offers WHERE id = :id",
                        new MapSqlParameterSource("id", offerId.value()),
                        String.class);
        assertThat(offerStatus)
                .as(
                        "the offer claim from step 1 must roll back — an ACCEPTED offer with no"
                                + " assignment behind it is exactly the half-committed state the"
                                + " transaction exists to prevent")
                .isEqualTo("OFFERED");
    }

    // ---------------------------------------------------------------- counters

    @Test
    @DisplayName("every invariant counter is observable non-zero — an unseen counter is untested")
    void countersCanFire() {
        for (InvariantCounters.Invariant invariant : InvariantCounters.Invariant.values()) {
            double before = counters.violationCount(invariant);
            counters.injectViolationForTest(invariant);
            assertThat(counters.violationCount(invariant))
                    .as("%s counter must be wired and incrementable", invariant)
                    .isEqualTo(before + 1);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Releases N tasks simultaneously on a latch, so they contend rather than queue. */
    private <T> List<T> raceAll(int n, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 64));
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger index = new AtomicInteger();

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
            futures.add(pool.submit(wrapLostClaims(callable)));
        }
        startGun.countDown();

        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        index.get();
        return results;
    }

    @SuppressWarnings("unchecked")
    private <T> Callable<T> wrapLostClaims(Callable<T> callable) {
        return () -> {
            try {
                return callable.call();
            } catch (AtomicClaimExecutor.ClaimLostException e) {
                return (T) e.result();
            }
        };
    }

    private List<CourierId> seedCouriers(int count) {
        List<CourierId> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO dispatch.couriers (id, display_name, status, last_position,
                                                   last_position_at)
                    VALUES (:id, :name, 'AVAILABLE',
                            ST_SetSRID(ST_MakePoint(10.18, 36.80), 4326)::geography, now())
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("name", "courier-" + i));
            ids.add(CourierId.of(id));
        }
        return ids;
    }

    private int countAssignments() {
        Integer n =
                jdbc.queryForObject(
                        "SELECT count(*) FROM dispatch.assignments",
                        new MapSqlParameterSource(),
                        Integer.class);
        return n == null ? 0 : n;
    }

    private String courierStatus(CourierId courierId) {
        return jdbc.queryForObject(
                "SELECT status::text FROM dispatch.couriers WHERE id = :id",
                new MapSqlParameterSource("id", courierId.value()),
                String.class);
    }

    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
