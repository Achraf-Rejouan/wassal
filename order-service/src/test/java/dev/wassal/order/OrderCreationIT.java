package dev.wassal.order;

import static org.assertj.core.api.Assertions.assertThat;

import dev.wassal.order.api.CreateOrderRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests against a real PostGIS container.
 *
 * <p>Nothing is mocked here, and that is a rule rather than a preference (NFR-009): a mocked
 * database cannot demonstrate that {@code ON CONFLICT DO NOTHING} arbitrates concurrent duplicates,
 * and demonstrating exactly that is the point of the idempotency test below.
 *
 * <p>Kafka is excluded from this suite so it stays fast; the full cross-service path is proven by
 * {@code WalkingSkeletonIT} in the proof module.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderCreationIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("postgis/postgis:16-3.4")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("wassal")
                    .withUsername("wassal")
                    .withPassword("wassal");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The Kafka publisher would poll against a broker that is not here.
        registry.add("wassal.outbox.poll-interval-ms", () -> "3600000");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @LocalServerPort int port;

    @Autowired TestRestTemplate rest;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private UUID merchantId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        // Truncation between tests rather than rollback: the commit behaviour of the unique
        // constraint is exactly what is under test, and a rolled-back transaction never
        // exercises it.
        jdbc.update(
                "TRUNCATE orders.idempotency_keys, orders.order_outbox, orders.orders CASCADE",
                new MapSqlParameterSource());
    }

    @Test
    @DisplayName("creating an order writes the order and its outbox row in one transaction")
    void createWritesOrderAndOutboxAtomically() {
        ResponseEntity<Map> response = createOrder("key-atomic-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID orderId = UUID.fromString((String) response.getBody().get("id"));

        assertThat(count("SELECT count(*) FROM orders.orders WHERE id = :id", "id", orderId))
                .isEqualTo(1);

        List<Map<String, Object>> outbox =
                jdbc.queryForList(
                        "SELECT event_type, aggregate_id, sent_at FROM orders.order_outbox",
                        new MapSqlParameterSource());

        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).get("event_type")).isEqualTo("OrderCreated");
        assertThat(outbox.get(0).get("aggregate_id")).isEqualTo(orderId);
        assertThat(outbox.get(0).get("sent_at"))
                .as("sent_at must stay null until the broker acknowledges — INV-5")
                .isNull();
    }

    @Test
    @DisplayName("replaying the same idempotency key returns the original order, not a new one")
    void replayReturnsOriginal() {
        ResponseEntity<Map> first = createOrder("key-replay-1");
        ResponseEntity<Map> second = createOrder("key-replay-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode())
                .as("a replay is a 200, not a 201 — the distinction is part of the contract")
                .isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));

        assertThat(count("SELECT count(*) FROM orders.orders", null, null)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders.order_outbox", null, null))
                .as("a replay must not emit a second event")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent requests with one key produce exactly one order — FR-001, INV-3 shape")
    void concurrentDuplicatesProduceOneOrder() throws Exception {
        int attempts = 24;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Callable<ResponseEntity<Map>>> tasks =
                IntStream.range(0, attempts)
                        .<Callable<ResponseEntity<Map>>>mapToObj(
                                i ->
                                        () -> {
                                            startGun.await();
                                            return createOrder("key-concurrent");
                                        })
                        .toList();

        List<Future<ResponseEntity<Map>>> futures = tasks.stream().map(pool::submit).toList();
        startGun.countDown(); // release all at once — sequential retries would prove nothing
        List<ResponseEntity<Map>> results = futures.stream().map(OrderCreationIT::get).toList();
        pool.shutdown();

        long created =
                results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long replayed = results.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();

        assertThat(created)
                .as("the database, not the application, must arbitrate concurrent duplicates")
                .isEqualTo(1);
        assertThat(replayed).isEqualTo(attempts - 1);
        assertThat(count("SELECT count(*) FROM orders.orders", null, null)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders.order_outbox", null, null)).isEqualTo(1);

        // Every replay must return the same id as the winner — otherwise a client that retried
        // would be handed an order it did not create.
        assertThat(results.stream().map(r -> r.getBody().get("id")).distinct()).hasSize(1);
    }

    @Test
    @DisplayName("the same key with a different payload is rejected, not silently replayed")
    void keyReuseWithDifferentPayloadIsRejected() {
        createOrder("key-reuse");

        ResponseEntity<Map> conflicting =
                post("key-reuse", new CreateOrderRequest.Coordinates(36.9, 10.3));

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflicting.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("SELECT count(*) FROM orders.orders", null, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("an order is queryable and starts PENDING")
    void orderIsQueryable() {
        UUID orderId = UUID.fromString((String) createOrder("key-query").getBody().get("id"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", merchantId.toString());

        ResponseEntity<Map> got =
                rest.exchange(
                        "http://localhost:" + port + "/v1/orders/" + orderId,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(got.getBody().get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("another merchant cannot read the order — ownership is a query predicate")
    void otherMerchantCannotRead() {
        UUID orderId = UUID.fromString((String) createOrder("key-auth").getBody().get("id"));

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Merchant-Id", UUID.randomUUID().toString());

        ResponseEntity<Map> got =
                rest.exchange(
                        "http://localhost:" + port + "/v1/orders/" + orderId,
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        Map.class);

        assertThat(got.getStatusCode())
                .as("404 rather than 403 — absent and not-yours are never distinguished")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers ----

    private ResponseEntity<Map> createOrder(String key) {
        return post(key, new CreateOrderRequest.Coordinates(36.8189, 10.1658));
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> post(String key, CreateOrderRequest.Coordinates dropoff) {
        String body =
                """
                {"pickup":{"lat":36.8065,"lon":10.1815},
                 "dropoff":{"lat":%s,"lon":%s}}
                """
                        .formatted(dropoff.lat(), dropoff.lon());
        return rest.exchange(
                url(), HttpMethod.POST, new HttpEntity<>(body, jsonHeaders(key)), Map.class);
    }

    private HttpHeaders jsonHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Merchant-Id", merchantId.toString());
        headers.set("Idempotency-Key", key);
        return headers;
    }

    private String url() {
        return "http://localhost:" + port + "/v1/orders";
    }

    private long count(String sql, String param, Object value) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (param != null) {
            params.addValue(param, value);
        }
        Long result = jdbc.queryForObject(sql, params, Long.class);
        return result == null ? 0 : result;
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
