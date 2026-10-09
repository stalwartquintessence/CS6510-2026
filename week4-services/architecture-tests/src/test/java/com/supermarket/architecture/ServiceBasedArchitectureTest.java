package com.supermarket.architecture;

import com.supermarket.persistence.ItemDao;
import com.supermarket.persistence.PopularItemsDao;
import com.supermarket.persistence.ScanLogDao;
import com.supermarket.persistence.TransactionDao;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable proof that the system really is service-based: independent deployables
 * whose only coupling is the shared database, reached through one shared library.
 *
 * <p>Each service lives in its own Maven module, so a rule that spans services can
 * only be written here, in the one module that has all of them on its classpath. The
 * services' own build cannot see each other — which is itself the strongest rule — but
 * this stops anyone adding the missing dependency.
 */
@AnalyzeClasses(
        packages = "com.supermarket",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ServiceBasedArchitectureTest {

    private static final List<String> SERVICES = List.of(
            "com.supermarket.catalog..",
            "com.supermarket.transaction..",
            "com.supermarket.inventory..",
            "com.supermarket.analytics..",
            "com.supermarket.gateway..");

    private static final String[] DOMAIN_SERVICES = {
            "com.supermarket.catalog..",
            "com.supermarket.transaction..",
            "com.supermarket.inventory..",
            "com.supermarket.analytics.."};

    /**
     * "Services should not communicate directly": no service names another service's
     * code. (Their classes are in separate jars at runtime anyway, so a reference would
     * be a {@code NoClassDefFoundError} waiting to happen.)
     */
    @ArchTest
    static void servicesDoNotDependOnEachOther(JavaClasses classes) {
        for (String service : SERVICES) {
            String[] others = SERVICES.stream().filter(s -> !s.equals(service)).toArray(String[]::new);
            noClasses()
                    .that().resideInAPackage(service)
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .because(service + " must reach other services only through the database or the gateway's RPCs")
                    .check(classes);
        }
    }

    /**
     * Only the gateway holds RPC clients. A domain service that opened a channel to another
     * would be exactly the direct service-to-service call this architecture avoids.
     */
    @ArchTest
    static final ArchRule onlyTheGatewayMakesRpcCalls = noClasses()
            .that().resideInAnyPackage(DOMAIN_SERVICES)
            .should().dependOnClassesThat().haveFullyQualifiedName("io.grpc.ManagedChannel")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("BlockingStub")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("FutureStub")
            .because("domain services serve RPCs; they never make them");

    /** The gateway is pure translation: no database, no domain kernel, no JPA. */
    @ArchTest
    static final ArchRule gatewayHasNoDatabase = noClasses()
            .that().resideInAPackage("com.supermarket.gateway..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.supermarket.persistence..",
                    "com.supermarket.domain..",
                    "jakarta.persistence..",
                    "org.springframework.data..",
                    "javax.sql..")
            .because("the gateway must stay a stateless edge that any number of copies could run");

    /** HTTP is the gateway's business alone; the services speak gRPC. */
    @ArchTest
    static final ArchRule onlyTheGatewayKnowsHttp = noClasses()
            .that().resideInAnyPackage(DOMAIN_SERVICES)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
            .because("the REST contract is the gateway's; services expose RPCs");

    // --- Shared database access: one library, one owner per table -------------------------------

    /** Entities and Spring Data are an implementation detail of the shared library. */
    @ArchTest
    static final ArchRule persistenceTechStaysInTheSharedLibrary = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.supermarket.persistence.entity..",
                    "org.springframework.data..",
                    "jakarta.persistence..")
            .because("services see the DAO ports and domain types, never how they are implemented");

    /**
     * Which service may <em>write</em> which table. The shared library gives every service
     * every DAO, so without this the "one owner per table" discipline would live only in
     * people's heads. Reads are open to anyone — that is what a shared database is for.
     */
    @ArchTest
    static final ArchRule onlyTransactionServiceDecrementsStock = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.transaction..")
            .should().callMethod(ItemDao.class, "decrementStockForUpdate", String.class, int.class)
            .because("the stock decrement shares a database transaction with the basket");

    @ArchTest
    static final ArchRule onlyCatalogServiceSeedsItems = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.catalog..")
            .should().callMethod(ItemDao.class, "saveAll", List.class)
            .because("the catalog service owns the existence of items");

    @ArchTest
    static final ArchRule onlyTransactionServiceWritesBaskets = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.transaction..")
            .should().callMethod(TransactionDao.class, "create", String.class, String.class)
            .orShould().callMethod(TransactionDao.class, "save", com.supermarket.domain.Basket.class)
            .because("transactions and transaction_items belong to the transaction service");

    @ArchTest
    static final ArchRule onlyTransactionServiceAppendsToTheScanLog = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.transaction..")
            .should().callMethod(ScanLogDao.class, "append", String.class, java.time.Instant.class)
            .because("scan_log is the transaction service's outbox");

    @ArchTest
    static final ArchRule onlyAnalyticsServiceWritesTheRanking = noClasses()
            .that().resideOutsideOfPackage("com.supermarket.analytics..")
            .should().callMethod(PopularItemsDao.class, "replaceRanking", List.class)
            .because("popular_items belongs to the analytics service");
}
