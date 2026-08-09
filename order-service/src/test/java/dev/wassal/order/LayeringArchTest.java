package dev.wassal.order;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering rule from docs/coding-standards.md, enforced rather than described.
 *
 * <p>An unenforced layout rule has a half-life of about a month. These are the rules that protect
 * the invariants: once a JPA annotation lands on a domain class, the state machine becomes
 * untestable without a database and INV-4's proof goes with it.
 */
@AnalyzeClasses(packages = "dev.wassal.order", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringArchTest {

    @ArchTest
    static final ArchRule domainIsFrameworkFree =
            noClasses()
                    .that()
                    .resideInAPackage("..order.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "jakarta.persistence..",
                            "org.springframework.data..",
                            "org.springframework.web..",
                            "org.apache.kafka..",
                            "io.lettuce..",
                            "com.fasterxml.jackson..")
                    .because(
                            "domain carries the invariants and must be unit-testable without"
                                    + " infrastructure; a JPA annotation here makes the state machine"
                                    + " untestable without a database");

    @ArchTest
    static final ArchRule apiDoesNotReachIntoInfra =
            noClasses()
                    .that()
                    .resideInAPackage("..order.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..order.infra..")
                    .because(
                            "a controller reaching a repository directly bypasses the domain"
                                    + " service where the invariant checks live");

    @ArchTest
    static final ArchRule domainDoesNotDependOnInfra =
            noClasses()
                    .that()
                    .resideInAPackage("..order.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..order.infra..", "..order.api..")
                    .because(
                            "dependencies point inward; infra implements ports declared in domain");

    @ArchTest
    static final ArchRule noCrossServiceImports =
            noClasses()
                    .that()
                    .resideInAPackage("..order..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "dev.wassal.dispatch..",
                            "dev.wassal.tracking..",
                            "dev.wassal.gateway..",
                            "dev.wassal.simulator..")
                    .because(
                            "two services sharing a domain class is how a distributed monolith is"
                                    + " built by accident; contracts is the only shared module");
}
