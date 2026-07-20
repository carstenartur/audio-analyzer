package org.hammer.audio.workflow.execution;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Architecture fitness checks for the run core and its HTTP adapter. */
class WorkflowRunArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("org.hammer.audio");

  @Test
  void runCoreDoesNotDependOnFrameworkDspOrStorageImplementations() {
    noClasses()
        .that()
        .resideInAPackage("org.hammer.audio.workflow.execution")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "org.eclipse.jgit..",
            "org.hibernate..",
            "javax.swing..",
            "java.awt..",
            "org.hammer.audio.dsp..",
            "org.hammer.audio.infrastructure..")
        .check(CLASSES);
  }

  @Test
  void runHttpAdapterDoesNotDependOnJgitHibernateOrInfrastructureAdapters() {
    noClasses()
        .that()
        .resideInAPackage("org.hammer.audio.workflow.execution.http..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.eclipse.jgit..", "org.hibernate..", "org.hammer.audio.infrastructure..")
        .check(CLASSES);
  }
}
