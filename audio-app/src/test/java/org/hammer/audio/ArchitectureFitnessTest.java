package org.hammer.audio;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit-based fitness tests that continuously enforce the architectural boundaries defined in
 * {@code docs/architecture/bounded-contexts.md}.
 *
 * <p>Rules implemented here:
 *
 * <ul>
 *   <li>Workflow context must not depend on Swing, JGit or the Persistence context.
 *   <li>Execution context must not depend on UI (Swing) or the Persistence context.
 *   <li>Validation is co-located in the Workflow package and therefore covered by the Workflow
 *       rules above.
 *   <li>No cyclic dependencies between the bounded-context package slices.
 * </ul>
 *
 * <p>Note: React and Yjs are JavaScript frameworks without a Java package equivalent in this
 * project. The workflow domain model is already protected from any Java-based UI or collaboration
 * framework by the rules in this class.
 *
 * <p>Module-level POM dependency rules are enforced separately in {@link
 * ArchitectureBoundaryTest#modulePomDependenciesPreserveStableBoundaries()}.
 */
class ArchitectureFitnessTest {

  private static final String WORKFLOW_PKG = "org.hammer.audio.workflow..";
  private static final String EXECUTION_PKG = "org.hammer.audio.workflow.execution..";
  private static final String RECORDING_PKG = "org.hammer.audio.recording..";
  private static final String UI_PKG = "org.hammer.audio.ui..";
  private static final String JAVAX_SWING = "javax.swing..";
  private static final String JAVA_AWT = "java.awt..";
  private static final String JGIT_PKG = "org.eclipse.jgit..";

  private static JavaClasses importProduction(String... packages) {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(packages);
  }

  /**
   * Bounded-context rule: the Workflow context (including Execution and Validation sub-contexts)
   * must not depend on Swing or AWT.
   *
   * <p>The workflow domain model is a framework-independent graph description. All visual rendering
   * is the exclusive responsibility of the Visualization context.
   */
  @Test
  void workflowContextDoesNotDependOnSwing() {
    JavaClasses classes = importProduction("org.hammer.audio.workflow");
    noClasses()
        .that()
        .resideInAPackage(WORKFLOW_PKG)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(JAVAX_SWING, JAVA_AWT)
        .check(classes);
  }

  /**
   * Bounded-context rule: the Workflow context must not depend on JGit.
   *
   * <p>Version-control and snapshot-persistence concerns belong to the Persistence context, not the
   * Workflow domain model.
   */
  @Test
  void workflowContextDoesNotDependOnJGit() {
    JavaClasses classes = importProduction("org.hammer.audio.workflow");
    noClasses()
        .that()
        .resideInAPackage(WORKFLOW_PKG)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(JGIT_PKG)
        .check(classes);
  }

  /**
   * Bounded-context rule: the Workflow context must not depend on the Persistence context.
   *
   * <p>This rule covers {@code WorkflowValidator} (the Validation sub-context), which is currently
   * co-located in the Workflow package. Neither the Workflow domain model nor the Validation logic
   * may import from {@code org.hammer.audio.recording}.
   */
  @Test
  void workflowContextDoesNotDependOnPersistence() {
    JavaClasses classes = importProduction("org.hammer.audio.workflow");
    noClasses()
        .that()
        .resideInAPackage(WORKFLOW_PKG)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(RECORDING_PKG)
        .check(classes);
  }

  /**
   * Bounded-context rule: the Execution context must not depend on the Persistence context.
   *
   * <p>Execution is a pure runtime-state model. It must never import recording or persistence
   * utilities.
   */
  @Test
  void executionContextDoesNotDependOnPersistence() {
    JavaClasses classes = importProduction("org.hammer.audio.workflow.execution");
    noClasses()
        .that()
        .resideInAPackage(EXECUTION_PKG)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(RECORDING_PKG)
        .check(classes);
  }

  /**
   * Bounded-context rule: the Execution context must not depend on the Visualization context.
   *
   * <p>Execution state is consumed by the Visualization context, never the other way around.
   */
  @Test
  void executionContextDoesNotDependOnVisualization() {
    JavaClasses classes = importProduction("org.hammer.audio.workflow.execution");
    noClasses()
        .that()
        .resideInAPackage(EXECUTION_PKG)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(UI_PKG, JAVAX_SWING, JAVA_AWT)
        .check(classes);
  }

  /**
   * Cycle-detection rule: the bounded-context package slices of {@code org.hammer.audio} must be
   * free of cyclic dependencies.
   *
   * <p>Each direct sub-package of {@code org.hammer.audio} (e.g. {@code workflow}, {@code
   * recording}, {@code ui}, {@code experimental}) is treated as one slice. ArchUnit verifies that
   * no two slices transitively depend on each other, preventing architectural drift over time.
   *
   * <p>Note: dependencies within the same slice (e.g. {@code workflow.execution → workflow}) are
   * intra-slice and are not evaluated by this rule.
   */
  @Test
  void noCyclicDependenciesBetweenBoundedContextSlices() {
    JavaClasses classes = importProduction("org.hammer.audio");
    slices().matching("org.hammer.audio.(*)..").should().beFreeOfCycles().check(classes);
  }
}
