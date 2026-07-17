from pathlib import Path

path = Path(
    "audio-app/src/main/java/org/hammer/audio/workflow/editor/http/"
    "WorkflowSessionApiModels.java"
)
text = path.read_text(encoding="utf-8")
old = (
    "  public record PresenceRequest(\n"
    "      @Valid @NotNull ActorRequest actor,\n"
    "      Instant observedAt,\n"
    "      @NotNull Map<String, String> attributes) {\n\n"
    "    PresenceState toDomain() {\n"
)
new = (
    "  public record PresenceRequest(\n"
    "      @Valid @NotNull ActorRequest actor,\n"
    "      Instant observedAt,\n"
    "      @NotNull Map<String, String> attributes) {\n\n"
    "    public PresenceRequest {\n"
    "      Objects.requireNonNull(actor, \"actor\");\n"
    "      attributes = Map.copyOf(Objects.requireNonNull(attributes, \"attributes\"));\n"
    "    }\n\n"
    "    PresenceState toDomain() {\n"
)
if text.count(old) != 1:
    raise SystemExit("Unexpected PresenceRequest source")
path.write_text(text.replace(old, new), encoding="utf-8")
