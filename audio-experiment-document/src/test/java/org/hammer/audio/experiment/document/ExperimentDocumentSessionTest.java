package org.hammer.audio.experiment.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hammer.audio.workflow.Workflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentDocumentSessionTest {
  @TempDir Path directory;
  private final ExperimentDocumentService service = new ExperimentDocumentService(List.of());

  @Test
  void contextCorrelatesPreviewStatusAndRevisionWithoutOpeningAClosedSession() throws Exception {
    var session = session();
    var empty = session.context();
    assertNull(empty.preview());
    assertFalse(empty.status().open());
    assertEquals(0, empty.revision());
    var preview = service.preview(ExperimentDocumentSecurityTest.fixture("minimal.audioexp"));
    session.open(preview, null, ExperimentDocumentSession.DirtyDecision.DISCARD, null);
    var before = session.context();
    session.editWorkflow("incomplete workflow", before.revision());
    var after = session.context();
    assertEquals(preview, before.preview());
    assertEquals(before.preview().canonicalSha256(), before.status().canonicalSha256());
    assertEquals(before.revision() + 1, after.revision());
    assertTrue(after.status().dirty());
    assertFalse(empty.status().open());
  }

  @Test
  void invalidWorkflowDraftCannotBeSavedOrSilentlyDiscarded() throws Exception {
    var session = session();
    var preview = service.preview(ExperimentDocumentSecurityTest.fixture("minimal.audioexp"));
    session.open(preview, null, ExperimentDocumentSession.DirtyDecision.DISCARD, null);
    long before = session.revision();
    session.editWorkflow("invalid workflow draft", before);
    assertTrue(session.status().dirty());
    assertThrows(
        RuntimeException.class, () -> session.saveAs(directory.resolve("invalid.audioexp")));
    assertFalse(Files.exists(directory.resolve("invalid.audioexp")));
    assertThrows(
        IllegalStateException.class,
        () ->
            session.openIfCurrent(
                preview, null, ExperimentDocumentSession.DirtyDecision.DISCARD, null, before));
    assertEquals("invalid workflow draft", session.workflowText());
  }

  @Test
  void oldDownloadAcknowledgementCannotClearAnUnvalidatedDraft() throws Exception {
    ExperimentDocumentSession session = session();
    var preview = service.preview(ExperimentDocumentSecurityTest.fixture("minimal.audioexp"));
    session.open(preview, null, ExperimentDocumentSession.DirtyDecision.DISCARD, null);
    String downloaded = preview.canonicalSha256();
    session.editWorkflow("unsaved incomplete draft", session.revision());
    assertThrows(IllegalStateException.class, () -> session.downloaded(downloaded));
    assertTrue(session.status().dirty());
    assertFalse(session.open(preview, null, ExperimentDocumentSession.DirtyDecision.CANCEL, null));
    assertEquals("unsaved incomplete draft", session.workflowText());
  }

  @Test
  void dirtyCancelAndFailedSaveLeaveCurrentDocumentUnchanged() throws Exception {
    ExperimentDocumentSession session = session();
    var first = service.preview(ExperimentDocumentSecurityTest.fixture("minimal.audioexp"));
    session.open(first, null, ExperimentDocumentSession.DirtyDecision.DISCARD, null);
    Workflow workflow = service.workflow(first);
    session.updateWorkflow(
        new Workflow(
            workflow.id(), "Changed", workflow.nodes(), workflow.edges(), workflow.metadata()));
    byte[] before = session.bytes();
    var next = service.preview(ExperimentDocumentSecurityTest.fixture("full.audioexp"));
    assertFalse(session.open(next, null, ExperimentDocumentSession.DirtyDecision.CANCEL, null));
    assertArrayEquals(before, session.bytes());
    Path blocked = directory.resolve("directory.audioexp");
    Files.createDirectory(blocked);
    assertThrows(
        java.io.IOException.class,
        () -> session.open(next, null, ExperimentDocumentSession.DirtyDecision.SAVE, blocked));
    assertArrayEquals(before, session.bytes());
    assertTrue(session.status().dirty());
  }

  @Test
  void saveAsRecordsIdentityAndPreservesImportedOriginal() throws Exception {
    ExperimentDocumentSession session = session();
    Path original = directory.resolve("original.audioexp");
    Files.copy(ExperimentDocumentSecurityTest.fixture("minimal.audioexp"), original);
    byte[] bytes = Files.readAllBytes(original);
    session.open(
        service.preview(original), original, ExperimentDocumentSession.DirtyDecision.DISCARD, null);
    Path copy = directory.resolve("copy.audioexp");
    session.saveAs(copy);
    assertArrayEquals(bytes, Files.readAllBytes(original));
    assertEquals(copy, session.status().path());
    assertEquals(Instant.parse("2026-11-20T12:00:00Z"), session.status().lastSavedAt());
    assertFalse(session.status().dirty());
    assertThrows(java.io.IOException.class, () -> session.saveAs(original));
  }

  @Test
  void optionalUnknownSectionsRemainInspectableAndExportable() throws Exception {
    ExperimentDocumentSession session = session();
    var unknown =
        service.preview(ExperimentDocumentSecurityTest.fixture("unknown-optional-plugin.audioexp"));
    session.open(unknown, null, ExperimentDocumentSession.DirtyDecision.DISCARD, null);
    Path copy = directory.resolve("preserved.audioexp");
    session.saveAs(copy);
    assertEquals(unknown.document().pluginData(), service.preview(copy).document().pluginData());
    assertThrows(
        IllegalStateException.class, () -> session.updateWorkflow(service.workflow(unknown)));
    assertTrue(session.status().readOnly());
  }

  private ExperimentDocumentSession session() {
    return new ExperimentDocumentSession(
        service, Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC));
  }
}
