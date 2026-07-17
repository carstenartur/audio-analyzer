from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected source in {path}: expected one occurrence of {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def replace_first(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Unexpected source in {path}: missing {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


hub = Path(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/"
    "WorkflowSessionEventHub.java"
)
replace_once(
    hub,
    "import java.util.concurrent.ArrayBlockingQueue;\n",
    "import java.util.concurrent.ArrayBlockingQueue;\n"
    "import java.util.concurrent.BlockingQueue;\n",
)
replace_once(
    hub,
    "  static final int DEFAULT_REPLAY_CAPACITY = 256;\n",
    "  private static final String ACTOR_FIELD = \"actor\";\n\n"
    "  static final int DEFAULT_REPLAY_CAPACITY = 256;\n",
)
replace_once(
    hub,
    "private final ArrayBlockingQueue<WorkflowSessionEvent> queue;",
    "private final BlockingQueue<WorkflowSessionEvent> queue;",
)
text = hub.read_text(encoding="utf-8")
actor_literal = 'Objects.requireNonNull(actor, "actor")'
if text.count(actor_literal) != 4:
    raise SystemExit("Expected four actor null-check literals")
hub.write_text(
    text.replace(actor_literal, "Objects.requireNonNull(actor, ACTOR_FIELD)"),
    encoding="utf-8",
)

registry = Path(
    "audio-core/src/main/java/org/hammer/audio/workflow/collaboration/"
    "WorkflowSessionRegistry.java"
)
for old, new in (
    (
        "  private final WorkflowSessionEventHub eventHub;\n",
        "  private final WorkflowSessionEventHub sessionEventHub;\n",
    ),
    (
        "    this.eventHub = Objects.requireNonNull(eventHub, \"eventHub\");\n",
        "    this.sessionEventHub = Objects.requireNonNull(eventHub, \"eventHub\");\n",
    ),
    ("    return eventHub;\n", "    return sessionEventHub;\n"),
    (
        "new SessionEntry(requiredSessionId, mode, owner, initialWorkflow, eventHub);",
        "new SessionEntry(requiredSessionId, mode, owner, initialWorkflow, sessionEventHub);",
    ),
    (
        "    eventHub.openSession(requiredSessionId, owner, initialWorkflow);\n",
        "    sessionEventHub.openSession(requiredSessionId, owner, initialWorkflow);\n",
    ),
    (
        "    eventHub.closeSession(requiredSessionId, entry.owner());\n",
        "    sessionEventHub.closeSession(requiredSessionId, entry.sessionOwner());\n",
    ),
    (
        "    OperationActor owner() {\n      return owner;\n    }",
        "    OperationActor sessionOwner() {\n      return owner;\n    }",
    ),
):
    replace_first(registry, old, new)

for relative_path in (
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowEditorHttpAdapter.java",
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowOperationHttpParser.java",
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowSessionApiModels.java",
):
    replace_once(
        Path(relative_path),
        "import com.fasterxml.jackson.databind.JsonNode;",
        "import tools.jackson.databind.JsonNode;",
    )

parser = Path(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowOperationHttpParser.java"
)
replace_once(
    parser,
    "  private WorkflowOperationHttpParser() {}",
    "  private WorkflowOperationHttpParser() {\n"
    "    throw new AssertionError(\"No instances\");\n"
    "  }",
)

models = Path(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowSessionApiModels.java"
)
replace_once(
    models,
    "  /** Request for one server-authoritative semantic session operation. */\n"
    "  public record SessionOperationRequest(\n"
    "      @NotNull CollaborationMode mode,\n"
    "      @Valid @NotNull ActorRequest actor,\n"
    "      @NotNull JsonNode operation) {}\n\n"
    "  /** Request for non-semantic cursor, selection or viewport presence state. */\n",
    "  /**\n"
    "   * Request for one server-authoritative semantic session operation.\n"
    "   *\n"
    "   * @param mode collaboration mode expected by the session\n"
    "   * @param actor authenticated actor applying the operation\n"
    "   * @param operation semantic workflow operation payload\n"
    "   */\n"
    "  public record SessionOperationRequest(\n"
    "      @NotNull CollaborationMode mode,\n"
    "      @Valid @NotNull ActorRequest actor,\n"
    "      @NotNull JsonNode operation) {\n"
    "    public SessionOperationRequest {\n"
    "      Objects.requireNonNull(mode, \"mode\");\n"
    "      Objects.requireNonNull(actor, \"actor\");\n"
    "      Objects.requireNonNull(operation, \"operation\");\n"
    "    }\n"
    "  }\n\n"
    "  /**\n"
    "   * Request for non-semantic cursor, selection or viewport presence state.\n"
    "   *\n"
    "   * @param actor actor publishing presence\n"
    "   * @param observedAt client observation time; server time is used when omitted\n"
    "   * @param attributes transport-neutral presence attributes\n"
    "   */\n",
)
replace_once(
    models,
    "  /** Stable transport response for accepted presence state. */\n"
    "  public record PresenceResponse(\n",
    "  /**\n"
    "   * Stable transport response for accepted presence state.\n"
    "   *\n"
    "   * @param actorId stable actor identifier\n"
    "   * @param observedAt accepted observation timestamp\n"
    "   * @param attributes immutable presence attributes\n"
    "   */\n"
    "  public record PresenceResponse(\n",
)
replace_once(
    models,
    "  /** Ordered SSE payload derived from a transport-neutral session event. */\n"
    "  public record SessionEventResponse(\n",
    "  /**\n"
    "   * Ordered SSE payload derived from a transport-neutral session event.\n"
    "   *\n"
    "   * @param eventId stable SSE event identifier\n"
    "   * @param sessionId collaboration session identifier\n"
    "   * @param sequence monotonically increasing session event sequence\n"
    "   * @param revision current semantic workflow revision\n"
    "   * @param occurredAt server event timestamp\n"
    "   * @param type event type\n"
    "   * @param actor actor associated with the event, when present\n"
    "   * @param operationId semantic operation identifier, when present\n"
    "   * @param projection canonical workflow projection, when present\n"
    "   * @param attributes immutable event attributes\n"
    "   */\n"
    "  public record SessionEventResponse(\n",
)
