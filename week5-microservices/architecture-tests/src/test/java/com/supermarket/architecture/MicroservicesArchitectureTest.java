package com.supermarket.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable proof that the system really is microservices: independently deployable
 * services that each own their data, reach each other only through the RPC contracts,
 * and only along an agreed, acyclic call graph.
 *
 * <p>Each service lives in its own Maven module, so a rule that spans services can only
 * be written here, in the one module that has all of them on its classpath. Week 4's
 * rules here were about <em>who may write which table</em> of the shared database; with
 * a database per service there is no shared table left to police, so those rules are
 * gone. What replaces them is the call graph, because services may now call each other.
 */
@AnalyzeClasses(
        packages = "com.supermarket",
        importOptions = ImportOption.DoNotIncludeTests.class)
class MicroservicesArchitectureTest {

    private static final List<String> SERVICES = List.of(
            "com.supermarket.catalog..",
            "com.supermarket.transaction..",
            "com.supermarket.inventory..",
            "com.supermarket.analytics..",
            "com.supermarket.gateway..");

    private static final String[] BUSINESS_SERVICES = {
            "com.supermarket.catalog..",
            "com.supermarket.transaction..",
            "com.supermarket.inventory..",
            "com.supermarket.analytics.."};

    /**
     * No service names another service's code — not its domain types, not its DAOs, not
     * its entities. (Their classes are in separate jars at runtime anyway.) Together with
     * the database-per-service check this is what "owns its data" means: the only way to
     * another service's data is its RPCs.
     */
    @ArchTest
    static void servicesDoNotDependOnEachOther(com.tngtech.archunit.core.domain.JavaClasses classes) {
        for (String service : SERVICES) {
            String[] others = SERVICES.stream().filter(s -> !s.equals(service)).toArray(String[]::new);
            noClasses()
                    .that().resideInAPackage(service)
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .because(service + " must reach other services only through their gRPC contracts")
                    .check(classes);
        }
    }

    // --- The call graph -----------------------------------------------------------------------
    //
    //   gateway ──▶ catalog, transaction, inventory, analytics   (the public RPCs only)
    //   transaction ──▶ catalog (item cache), inventory's StockService (the saga)
    //   inventory ──▶ catalog (seeding)
    //   analytics ──▶ transaction's ScanFeed (scans), catalog (names)
    //   catalog ──▶ nothing
    //
    // Holding a service's stub is the only way to call it, so "may hold the stub" is the edge.
    // The graph is acyclic: catalog is a leaf, and nothing calls analytics but the gateway.

    @ArchTest
    static final ArchRule catalogCallsNoOne = mayOnlyCall("com.supermarket.catalog..")
            .because("the catalog is the leaf of the call graph: everyone may depend on it, it depends on no one");

    @ArchTest
    static final ArchRule inventoryCallsOnlyTheCatalog = mayOnlyCall("com.supermarket.inventory..", "CatalogService")
            .because("inventory reads the catalog once, to seed its stock rows");

    @ArchTest
    static final ArchRule transactionCallsTheCatalogAndTheStockStep =
            mayOnlyCall("com.supermarket.transaction..", "CatalogService", "StockService")
                    .because("checkout caches the catalog and orchestrates the saga's stock step; nothing else");

    @ArchTest
    static final ArchRule analyticsCallsTheScanFeedAndTheCatalog =
            mayOnlyCall("com.supermarket.analytics..", "ScanFeed", "CatalogService")
                    .because("analytics consumes scans from the transaction service's outbox and names from the catalog");

    @ArchTest
    static final ArchRule gatewayCallsOnlyThePublicServices = mayOnlyCall("com.supermarket.gateway..",
            "CatalogService", "TransactionService", "InventoryService", "AnalyticsService")
            .because("StockService and ScanFeed are internal: no client request may move stock or read the outbox directly");

    // --- Data ownership ------------------------------------------------------------------------

    /** The gateway is pure translation: no database, no JPA. */
    @ArchTest
    static final ArchRule gatewayHasNoDatabase = noClasses()
            .that().resideInAPackage("com.supermarket.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..persistence..",
                    "jakarta.persistence..",
                    "org.springframework.data..",
                    "javax.sql..")
            .because("the gateway must stay a stateless edge that any number of copies could run");

    /**
     * JPA and Spring Data stay inside each service's own {@code persistence} package, so a
     * service's schema is an implementation detail that one package can change.
     */
    @ArchTest
    static final ArchRule persistenceStaysInEachServicesPersistencePackage = noClasses()
            .that().resideOutsideOfPackage("..persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..", "org.springframework.data..")
            .orShould().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
            .because("services expose DAO ports and domain types to themselves, never how they are stored");

    @ArchTest
    static final ArchRule entitiesLiveInAServicesPersistencePackage = classes()
            .that().areAnnotatedWith("jakarta.persistence.Entity")
            .should().resideInAnyPackage(
                    "com.supermarket.catalog.persistence..",
                    "com.supermarket.transaction.persistence..",
                    "com.supermarket.inventory.persistence..",
                    "com.supermarket.analytics.persistence..")
            .because("every table belongs to exactly one service");

    /**
     * What week 4 shared was a database access library; what week 5 shares is only the wire
     * contracts and the service chassis, and neither may know about persistence.
     */
    @ArchTest
    static final ArchRule nothingSharedTouchesADatabase = noClasses()
            .that().resideInAnyPackage("com.supermarket.contracts..", "com.supermarket.support..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework.data..", "org.springframework.jdbc..", "javax.sql..")
            .because("a shared persistence library is a shared schema, which is what this week removes");

    /** HTTP is the gateway's business alone; the services speak gRPC. */
    @ArchTest
    static final ArchRule onlyTheGatewayKnowsHttp = noClasses()
            .that().resideInAnyPackage(BUSINESS_SERVICES)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
            .because("the REST contract is the gateway's; services expose RPCs");

    // --- helpers -------------------------------------------------------------------------------

    private static ArchRule mayOnlyCall(String caller, String... allowedServices) {
        return noClasses()
                .that().resideInAPackage(caller)
                .should().dependOnClassesThat(stubs().and(not(stubsOf(allowedServices))));
    }

    /** Every generated client stub: {@code com.supermarket.contracts.XGrpc$X…Stub}. */
    private static DescribedPredicate<JavaClass> stubs() {
        return DescribedPredicate.describe("are gRPC client stubs", c ->
                c.getPackageName().equals("com.supermarket.contracts")
                        && c.getName().contains("Grpc$")
                        && c.getSimpleName().endsWith("Stub"));
    }

    private static DescribedPredicate<JavaClass> stubsOf(String... services) {
        Set<String> prefixes = Set.of(services);
        return DescribedPredicate.describe("stubs of " + prefixes, c -> prefixes.stream()
                .anyMatch(s -> c.getName().startsWith("com.supermarket.contracts." + s + "Grpc$")));
    }
}
