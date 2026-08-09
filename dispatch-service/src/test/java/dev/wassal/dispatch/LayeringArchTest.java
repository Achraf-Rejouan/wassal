package dev.wassal.dispatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Layering rules for the service that holds the claim.
 *
 * <p>These matter more here than anywhere else in the system: {@code dispatch-service} owns INV-1,
 * INV-2 and INV-3, and the reason its domain can be reasoned about at all is that the decision
 * logic is separable from the infrastructure that executes it.
 */
@AnalyzeClasses(
        packages = "dev.wassal.dispatch",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringArchTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree =
            noClasses()
                    .that()
                    .resideInAPackage("..dispatch.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "org.springframework.web..",
                            "org.springframework.jdbc..",
                            "org.apache.kafka..",
                            "io.lettuce..",
                            "redis.clients..")
                    .because(
                            "the claim's decision logic must be readable without knowing which"
                                    + " database executes it; a Redis or JDBC type in domain would"
                                    + " make ADR-0004 an implementation detail rather than a design");

    @ArchTest
    static final ArchRule domainDoesNotDependOnInfra =
            noClasses()
                    .that()
                    .resideInAPackage("..dispatch.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..dispatch.infra..", "..dispatch.api..")
                    .because(
                            "dependencies point inward; infra implements ports declared in domain");

    @ArchTest
    static final ArchRule noCrossServiceImports =
            noClasses()
                    .that()
                    .resideInAPackage("..dispatch..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "dev.wassal.order..",
                            "dev.wassal.tracking..",
                            "dev.wassal.gateway..",
                            "dev.wassal.simulator..")
                    .because(
                            "two services sharing a domain class is how a distributed monolith is"
                                    + " built by accident; contracts is the only shared module");

    /**
     * NFR-009, enforced rather than described. A mocked database cannot demonstrate that a partial
     * unique index rejects a second active assignment — which is the entire proof of INV-1 — so the
     * temptation to mock it away has to be closed off structurally.
     */
    @ArchTest
    static final ArchRule noRawSqlConcatenation =
            noClasses()
                    .that()
                    .resideInAPackage("..dispatch.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                    .because(
                            "controllers map results to status codes; they do not reach the"
                                    + " database directly, or the claim's transaction boundary stops"
                                    + " being the only place concurrency is decided");
}
