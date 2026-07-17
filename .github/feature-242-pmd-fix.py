from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected source in {path}: expected one occurrence of {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


event_adapter = Path(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowSessionEventHttpAdapter.java"
)
replace_once(
    event_adapter,
    "  @GetMapping(path = \"/{sessionId}/events\", produces = MediaType.TEXT_EVENT_STREAM_VALUE)\n",
    "  @SuppressWarnings(\"PMD.CloseResource\")\n"
    "  @GetMapping(path = \"/{sessionId}/events\", produces = MediaType.TEXT_EVENT_STREAM_VALUE)\n",
)
replace_once(
    event_adapter,
    "  private static final class SubscriptionHolder {\n",
    "  // The holder owns the subscription until an emitter completion callback closes it.\n"
    "  @SuppressWarnings(\"PMD.CloseResource\")\n"
    "  private static final class SubscriptionHolder {\n",
)
replace_once(
    event_adapter,
    "        WorkflowSessionEventHub.Subscription attachedSubscription = subscription.get();",
    "        WorkflowSessionEventHub.Subscription attachedSubscription = subscription.getAndSet(null);",
)

session_adapter = Path(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowSessionHttpAdapter.java"
)
replace_once(
    session_adapter,
    "import java.util.Objects;\n"
    "import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;\n",
    "import java.util.Objects;\n"
    "import org.hammer.audio.workflow.WorkflowOperation;\n"
    "import org.hammer.audio.workflow.collaboration.OperationActor;\n"
    "import org.hammer.audio.workflow.collaboration.WorkflowSessionRegistry;\n",
)
replace_once(
    session_adapter,
    "    var actor = request.actor().toDomain();\n"
    "    var operation =\n"
    "        WorkflowOperationHttpParser.parse(request.operation(), actor.actorId());\n",
    "    OperationActor actor = request.actor().toDomain();\n"
    "    WorkflowOperation operation =\n"
    "        WorkflowOperationHttpParser.parse(request.operation(), actor.actorId());\n",
)
replace_once(
    session_adapter,
    "    var actor = request.actor().toDomain();\n"
    "    return PresenceResponse.from(registry.updatePresence(sessionId, actor, request.toDomain()));\n",
    "    OperationActor actor = request.actor().toDomain();\n"
    "    return PresenceResponse.from(registry.updatePresence(sessionId, actor, request.toDomain()));\n",
)
