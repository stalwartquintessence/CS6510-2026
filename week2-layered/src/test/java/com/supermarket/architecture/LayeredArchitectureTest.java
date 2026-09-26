package com.supermarket.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Executable proof that this week's layering actually holds.
 *
 * <p>Every rule here corresponds to a boundary week 1 crossed somewhere. They
 * run as part of {@code ./mvnw test}, so a future change that reaches around a
 * layer fails the build instead of quietly eroding the architecture.
 */
@AnalyzeClasses(
        packages = "com.supermarket",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

    /**
     * The core rule: four layers over a shared domain kernel.
     *
     * <p>{@code transaction} may call {@code analytics} (every scan is recorded),
     * and both may call {@code persistence}. Nothing may call {@code api}, and
     * nothing below may reach back up.
     */
    @ArchTest
    static final ArchRule layers = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .layer("API").definedBy("com.supermarket.api..")
            .layer("Transactions").definedBy("com.supermarket.transaction..")
            .layer("Analytics").definedBy("com.supermarket.analytics..")
            .layer("Persistence").definedBy("com.supermarket.persistence..")
            .layer("Domain").definedBy("com.supermarket.domain..")

            .whereLayer("API").mayNotBeAccessedByAnyLayer()
            .whereLayer("Transactions").mayOnlyBeAccessedByLayers("API")
            .whereLayer("Analytics").mayOnlyBeAccessedByLayers("API", "Transactions")
            .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Transactions", "Analytics")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers(
                    "API", "Transactions", "Analytics", "Persistence");

    /**
     * JPA entities are an implementation detail of the database access layer.
     * In week 1 they were the currency of the whole application.
     */
    @ArchTest
    static final ArchRule entitiesStayInPersistence = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.persistence..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.supermarket.persistence.entity..")
            .because("entities must not escape the database access layer; "
                    + "upper layers work with the Basket aggregate and ItemSnapshot instead");

    /** Spring Data and JPA types belong to the database access layer only. */
    @ArchTest
    static final ArchRule persistenceTechStaysInPersistence = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.persistence..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.data..", "jakarta.persistence..")
            .because("only the database access layer may know how persistence is implemented");

    /**
     * Web types belong to the API layer only. Note this deliberately permits
     * {@code org.springframework.transaction..} further down: {@code @Transactional}
     * belongs on the service that owns the unit of work, which is exactly why
     * completion can hold a row lock across several decrements.
     */
    @ArchTest
    static final ArchRule webTechStaysInApi = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.api..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
            .because("HTTP is a delivery detail; only the API layer may name it");

    /** The domain kernel is a leaf — it must not depend on any other layer. */
    @ArchTest
    static final ArchRule domainIsALeaf = noClasses()
            .that().resideInAPackage("com.supermarket.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.supermarket.api..",
                    "com.supermarket.transaction..",
                    "com.supermarket.analytics..",
                    "com.supermarket.persistence..")
            .because("the shared kernel must be depend-on-able from everywhere");

    /** No package cycles anywhere in the application. */
    @ArchTest
    static final ArchRule noCycles = slices()
            .matching("com.supermarket.(*)..")
            .should().beFreeOfCycles();

    /**
     * Week 1's single worst coupling: the transactions and analytics services
     * both injected {@code InventoryRepository} directly, so nothing owned the
     * items table. Spring Data interfaces are now package-private inside
     * {@code persistence.jpa}; this rule states the intent explicitly.
     */
    @ArchTest
    static final ArchRule onlyDaosUseSpringDataRepositories = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.persistence.jpa..")
            .should().dependOnClassesThat()
            .haveNameMatching(".*JpaRepository")
            .because("the items table has exactly one owner: ItemDao");

}
