package dev.wassal.proof.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * NFR-012: the system fails <em>closed</em> when it cannot verify a precondition.
 *
 * <p>This is the property that makes every other guarantee trustworthy. An assignment granted while
 * the database was unreachable would be an assignment nobody can prove is unique, and a system that
 * degrades toward "yes" under failure has no invariants at all — only invariants that hold while
 * the infrastructure is healthy, which is not a claim worth making.
 *
 * <p>Toxiproxy sits between the client and Postgres so the failure is a real network fault rather
 * than a mocked exception. <strong>Latency injection matters as much as hard partition</strong>:
 * timeouts that cascade cause more outages than clean failures, and a system that behaves correctly
 * when the database is <em>gone</em> can still behave incorrectly when it is merely slow.
 */
@Testcontainers
class FailClosedUnderPartitionTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres")
                    .withDatabaseName("chaos")
                    .withUsername("chaos")
                    .withPassword("chaos");

    @Container
    @SuppressWarnings("resource")
    static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.11.0"))
                    .withNetwork(NETWORK);

    private static Proxy proxy;
    private static String proxiedJdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        if (proxy == null) {
            var client =
                    new eu.rekawek.toxiproxy.ToxiproxyClient(
                            TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            proxy = client.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
            proxiedJdbcUrl =
                    "jdbc:postgresql://%s:%d/chaos"
                            .formatted(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(8666));
        }
        clearToxics();

        try (Connection setup = direct()) {
            setup.createStatement()
                    .execute(
                            """
                            DROP TABLE IF EXISTS couriers;
                            CREATE TABLE couriers (id uuid PRIMARY KEY, status text NOT NULL);
                            DROP TABLE IF EXISTS assignments;
                            CREATE TABLE assignments (
                                id uuid PRIMARY KEY, courier_id uuid NOT NULL, status text NOT NULL);
                            CREATE UNIQUE INDEX uq_active ON assignments (courier_id)
                                WHERE status = 'ACTIVE';
                            """);
        }
    }

    @Test
    @DisplayName("a hard partition produces NO assignment — the claim fails closed")
    void hardPartitionCreatesNoAssignment() throws Exception {
        UUID courierId = seedCourier();

        proxy.toxics().bandwidth("cut-down", ToxicDirection.DOWNSTREAM, 0);
        proxy.toxics().bandwidth("cut-up", ToxicDirection.UPSTREAM, 0);

        // The claim must throw rather than proceed. "Could not verify" and "verified false" have
        // to produce the same outcome, or the invariant only holds while the network does.
        assertThatThrownBy(() -> attemptClaim(courierId))
                .as("a claim that cannot reach the database must fail, not assume")
                .isInstanceOf(Exception.class);

        clearToxics();

        assertThat(activeAssignments())
                .as("no assignment may exist from a claim made during the partition")
                .isZero();
        assertThat(courierStatus(courierId))
                .as("and the courier must not have been left BUSY by a half-applied claim")
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("severe latency does not produce a second assignment — slow is not permissive")
    void latencyDoesNotWeakenTheInvariant() throws Exception {
        UUID courierId = seedCourier();
        assertThat(attemptClaim(courierId)).isTrue();

        // 3s of injected latency: slow enough to time out naive code, not slow enough to look
        // like a partition. This is the regime where cascading-timeout bugs actually live.
        proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, 3_000);

        boolean secondClaim;
        try {
            secondClaim = attemptClaim(courierId);
        } catch (Exception e) {
            secondClaim = false; // timed out — also an acceptable, closed outcome
        }
        clearToxics();

        assertThat(secondClaim)
                .as("the courier was already claimed; latency must not turn that into a yes")
                .isFalse();
        assertThat(activeAssignments())
                .as("INV-1 holds under latency exactly as it does under health")
                .isOne();
    }

    @Test
    @DisplayName("the database enforces INV-1 even if the application logic is bypassed entirely")
    void constraintHoldsWithoutApplicationLogic() throws Exception {
        UUID courierId = seedCourier();
        assertThat(attemptClaim(courierId)).isTrue();

        // Insert straight into the table, skipping every guard the service has. If the invariant
        // depended on application code this would succeed — which is exactly the difference
        // between "enforced by code we tested" and "enforced by a constraint that cannot be
        // bypassed" (ADR-0004).
        try (Connection connection = direct();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO assignments VALUES (?, ?, 'ACTIVE')")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, courierId);
            assertThatThrownBy(insert::executeUpdate)
                    .as("the partial unique index must refuse a second active assignment")
                    .hasMessageContaining("uq_active");
        }

        assertThat(activeAssignments()).isOne();
    }

    @Test
    @DisplayName("recovery is automatic: claims succeed again once the partition heals")
    void recoversAfterPartitionHeals() throws Exception {
        UUID courierId = seedCourier();

        proxy.toxics().bandwidth("cut-down", ToxicDirection.DOWNSTREAM, 0);
        proxy.toxics().bandwidth("cut-up", ToxicDirection.UPSTREAM, 0);
        try {
            attemptClaim(courierId);
        } catch (Exception expected) {
            // The point of the partition.
        }
        clearToxics();

        // No compensating action, no cleanup job, no manual step — the failed claim left nothing
        // behind to undo, which is the property ADR-0004 chose the transactional store for.
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(attemptClaim(courierId)).isTrue());

        assertThat(activeAssignments()).isOne();
    }

    // ---------------------------------------------------------------- helpers

    /** The claim, in the same shape production uses: conditional update, affected-row check. */
    private boolean attemptClaim(UUID courierId) throws Exception {
        try (Connection connection = proxied()) {
            connection.setAutoCommit(false);
            try (PreparedStatement claim =
                    connection.prepareStatement(
                            "UPDATE couriers SET status='BUSY'"
                                    + " WHERE id = ? AND status = 'AVAILABLE'")) {
                claim.setObject(1, courierId);
                if (claim.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO assignments VALUES (?, ?, 'ACTIVE')")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, courierId);
                insert.executeUpdate();
            }
            connection.commit();
            return true;
        }
    }

    private UUID seedCourier() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = direct();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO couriers VALUES (?, 'AVAILABLE')")) {
            insert.setObject(1, id);
            insert.executeUpdate();
        }
        return id;
    }

    private int activeAssignments() throws Exception {
        try (Connection connection = direct();
                ResultSet rs =
                        connection
                                .createStatement()
                                .executeQuery(
                                        "SELECT count(*) FROM assignments WHERE status='ACTIVE'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String courierStatus(UUID courierId) throws Exception {
        try (Connection connection = direct();
                PreparedStatement query =
                        connection.prepareStatement("SELECT status FROM couriers WHERE id = ?")) {
            query.setObject(1, courierId);
            try (ResultSet rs = query.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static void clearToxics() throws Exception {
        for (var toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
    }

    /** Bypasses Toxiproxy — used to set up and to observe, never to exercise the claim. */
    private Connection direct() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Connection proxied() throws Exception {
        return DriverManager.getConnection(
                proxiedJdbcUrl + "?connectTimeout=3&socketTimeout=5",
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
