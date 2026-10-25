package com.supermarket.architecture;

import com.supermarket.analytics.pipeline.Stage;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Executable proof that the analytics module really is pipes and filters,
 * on top of week 2's layer rules in {@link LayeredArchitectureTest}.
 */
@AnalyzeClasses(
        packages = "com.supermarket",
        importOptions = ImportOption.DoNotIncludeTests.class)
class PipelineArchitectureTest {

    private static final String FRAMEWORK = "com.supermarket.analytics.pipeline..";

    /**
     * The pipes-and-filters mechanism knows nothing about checkout, analytics,
     * persistence or Spring. This also rules out any cycle between the framework
     * and the analytics stages built on it: the dependency can only point one way.
     */
    @ArchTest
    static final ArchRule frameworkIsGeneric = classes()
            .that().resideInAPackage(FRAMEWORK)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", FRAMEWORK)
            .because("Pipe/Stage/Filter/Sink are a generic mechanism; the application plugs into them, "
                    + "never the other way round");

    /** The concrete stages: everything that runs on a pipeline thread, minus the framework bases. */
    private static final com.tngtech.archunit.base.DescribedPredicate<JavaClass> CONCRETE_STAGES =
            assignableTo(Stage.class).and(resideOutsideOfPackage(FRAMEWORK)).as("concrete pipeline stages");

    /**
     * Filters never call, construct or even name each other. Their only
     * connection is the pipe between them, which is what lets a stage be
     * replaced, re-ordered or removed without touching its neighbours.
     */
    @ArchTest
    static final ArchRule stagesOnlyMeetThroughPipes = noClasses()
            .that(CONCRETE_STAGES)
            .should().dependOnClassesThat(CONCRETE_STAGES)
            .because("filters communicate only through pipes");

    /**
     * Window and Rank are pure transformations: they may use the JDK, the
     * framework, and the immutable message records flowing through the pipes —
     * no persistence, no Spring, no other analytics collaborators. Only Enrich
     * (catalog lookup) and Publish (the sink) touch the outside world.
     */
    @ArchTest
    static final ArchRule windowAndRankArePure = classes()
            .that().haveSimpleName("WindowFilter").or().haveSimpleName("RankFilter")
            .should().onlyDependOnClassesThat(
                    resideInAnyPackage("java..", FRAMEWORK).or(assignableTo(Record.class)))
            .because("windowing and ranking are pure functions of the messages they receive");
}
