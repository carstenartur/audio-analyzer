from pathlib import Path

path = Path("audio-core/src/main/java/org/hammer/audio/workflow/collaboration/WorkflowSessionRegistry.java")
text = path.read_text(encoding="utf-8")
text = text.replace(
    "          SessionEntry entry = SessionEntry.restore(restored);",
    "          SessionEntry entry = new SessionEntry(restored);",
)
old = '''        static SessionEntry restore(WorkflowSessionState state) {
          SessionEntry entry =
              WorkflowSessionRegistry.this
              .new SessionEntry(
                  state.sessionId(),
                  state.mode(),
                  state.owner(),
                  state.createdAt(),
                  state.initialWorkflow());
          entry.participants.clear();
          state.participants().forEach(actor -> entry.participants.put(actor.actorId(), actor));
          for (WorkflowOperation operation : state.operations()) {
            entry.operationLog.apply(operation);
            entry.operationsById.put(operation.operationId(), operation);
          }
          if (!entry.operationLog.currentWorkflow().equals(state.workflow())) {
            throw new IllegalStateException(
                "Restored operation replay diverges for session " + state.sessionId());
          }
          entry.revision = state.revision();
          entry.sequence = state.sequence();
          entry.closed = state.closed();
          return entry;
        }
'''
new = '''        SessionEntry(WorkflowSessionState state) {
          this(
              state.sessionId(),
              state.mode(),
              state.owner(),
              state.createdAt(),
              state.initialWorkflow());
          participants.clear();
          state.participants().forEach(actor -> participants.put(actor.actorId(), actor));
          for (WorkflowOperation operation : state.operations()) {
            operationLog.apply(operation);
            operationsById.put(operation.operationId(), operation);
          }
          if (!operationLog.currentWorkflow().equals(state.workflow())) {
            throw new IllegalStateException(
                "Restored operation replay diverges for session " + state.sessionId());
          }
          revision = state.revision();
          sequence = state.sequence();
          closed = state.closed();
        }
'''
if text.count(old) != 1:
    raise SystemExit("Unexpected generated restore method")
path.write_text(text.replace(old, new), encoding="utf-8")

test = Path("audio-core/src/test/java/org/hammer/audio/workflow/collaboration/WorkflowSessionPersistenceBoundaryTest.java")
text = test.read_text(encoding="utf-8")
text = text.replace(
    "        public void markPublished(String eventId, Instant publishedAt) {}",
    "        public void markPublished(String eventId, Instant publishedAt) {\n"
    "          // Not needed by this aggregate-boundary test.\n"
    "        }",
)
text = text.replace(
    "        public void markAttempt(String eventId, Instant nextAttemptAt, String errorMessage) {}",
    "        public void markAttempt(String eventId, Instant nextAttemptAt, String errorMessage) {\n"
    "          // Not needed by this aggregate-boundary test.\n"
    "        }",
)
test.write_text(text, encoding="utf-8")
