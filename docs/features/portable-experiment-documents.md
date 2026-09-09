# Portable experiment documents (`.audioexp`)

Audio Analyzer experiment documents are small, deterministic setup and reproducibility contracts.
They describe what an experiment is, which canonical workflow it uses, which portable assets and
outputs it expects and which plugin-owned sections are required. They do **not** embed recordings,
large datasets, credentials, local output directories or executable implementation references.

## Public identity

|         Property         |                             Value                             |
|--------------------------|---------------------------------------------------------------|
| Preferred extension      | `.audioexp`                                                   |
| Media type               | `application/vnd.carstenartur.audio-analyzer.experiment+json` |
| Format identifier        | `io.github.carstenartur.audio-analyzer.experiment`            |
| Current envelope version | `1`                                                           |
| Embedded workflow format | `io.github.carstenartur.audio-analyzer.workflow-dsl`          |
| Public schema resource   | `schemas/audio-analyzer-experiment-v1.schema.json`            |

New documents use the [public v1 schema URL](https://raw.githubusercontent.com/carstenartur/audio-analyzer/master/audio-experiment-document/src/main/resources/schemas/audio-analyzer-experiment-v1.schema.json)
as `$schema`. The earlier relative resource identity remains an accepted v1 alias and is preserved
when normalizing existing files. Neither identifier is fetched during import.
The [vendor media-type registration dossier](../specifications/audio-experiment-media-type.md)
records the proposed registration information; this implementation does not claim IANA registration.

The extension and media type are hints only. Every importer verifies `format`, `formatVersion`, the
checked-in schema identity, the canonical workflow hash and the canonical document hash.

## Scope

An `.audioexp` document contains:

- stable experiment identity, name, intent, tags and source mode;
- the canonical workflow DSL plus its independent format version and SHA-256;
- portable profile data;
- namespaced, versioned plugin sections;
- bounded relative asset references with media type, byte size and digest;
- logical output requests with portable basenames;
- creation and migration provenance;
- the canonical document SHA-256.

Recordings use the binary `.aarec` format. Complete evidence packages may later bundle documents,
recordings and results under a separate media type. Large binary data is never base64-embedded in an
experiment setup document.

## Determinism

The codec reparses and serializes the embedded workflow with `WorkflowDslParser` and
`WorkflowDslSerializer`. Object keys, plugin IDs, section IDs, assets and outputs are emitted in a
deterministic order. Equivalent decimal plugin values are normalized. Saving the same normalized
setup twice produces byte-identical UTF-8 JSON.

Two hashes have distinct purposes:

- `workflow.sha256` protects the canonical workflow DSL;
- `provenance.canonicalSha256` protects the complete normalized document while excluding the hash
  field itself from the hash input.

A mismatch is a validation error; the document is never silently accepted or repaired in place.

The canonical encoding has no BOM, insignificant whitespace or trailing newline. Core fields use the
order emitted by `ExperimentDocumentCodec`; extensible object keys use Java lexicographic order.
JSON numeric data is parsed as exact decimals and normalized with `BigDecimal.stripTrailingZeros()`;
large powers of ten may use exponent notation. Strings preserve their Unicode content. This is the
Audio Analyzer normal form, not an assertion of RFC 8785/JCS compatibility. The checked-in fixtures
provide byte-level examples for independent implementations.

## Core profiles and reproducibility

`profiles` is a closed core object. Each of its five sections is optional, but a present section must
contain the fields defined in the public schema:

|      Section      |                                                    Contents                                                     |
|-------------------|-----------------------------------------------------------------------------------------------------------------|
| `source`          | `assetId` referring to an entry in `assets`; required for `recording`, `replay` and `dataset` modes             |
| `capture`         | Sample rate in Hz, channel count, PCM sample size, signedness, byte order and logical device requirements       |
| `microphoneArray` | Stable identity, name and microphone positions in metres with zero-based channel mapping                        |
| `calibration`     | Identity, reference channel, channel timing/level parameters, validity interval and an evidence asset reference |
| `environment`     | Temperature in Celsius, relative humidity in percent, speed of sound in m/s and explanatory notes               |

Channel indices are unique and contiguous. Capture, geometry and calibration channel counts agree;
calibration validity ends after its creation instant. A calibration that has expired can still be
opened for historical inspection; storing a validity interval does not certify current hardware.
All source and calibration evidence references resolve to declared, bounded assets. Opening a
document does not read those assets: explicit local binding performs the size/digest checks later.

`provenance` additionally supports optional `algorithmVersions`, `sourceCommit` (Git object ID) and
`sourceRun` (stable run ID). Creator information, including `verifiedAccount`, is an imported claim;
the document hash is an integrity check, not a signature or identity verification.

Local device IDs, credentials and output directories have no core representation. Workflow metadata
cannot smuggle these bindings or implementation/script/command references into the portable setup.
Use logical output IDs and `outputs` instead of `output.path`. Unknown plugin payloads remain inert
data and are never used for reflection, shell execution, network retrieval or plugin installation.

## Import safety

All input is untrusted. The shared `ExperimentDocumentService` enforces the same rules for CLI,
REST, Swing and web adapters:

- maximum document size: 2 MiB;
- maximum nesting depth: 64;
- maximum collection size per level: 10,000;
- bounded string values;
- number tokens limited to 128 characters and parsed without floating-point rounding;
- exactly one UTF-8 JSON document, with no trailing JSON value;
- strict duplicate-key rejection;
- fixed allowlists for all current core fields;
- rejection of unsupported future envelope versions;
- no polymorphic object deserialization;
- no Java class names, scripts, commands or automatic plugin installation;
- no automatic URL or remote JSON Schema dereferencing;
- no absolute paths, path traversal or output directories in portable values;
- no local device opening or experiment execution during preview;
- no file write before an explicit normalize/save-as destination is selected.

Limits cover the entire envelope and plugin data during streaming, before tree allocation.
The complete bundled Draft 2020-12 schema is validated locally with network retrieval disabled.
Portable basenames also reject control characters, Windows device names and trailing dots/spaces.
Output names are unique ignoring case, so two declared outputs cannot collide on another platform.
Assets are relative paths of at most 1024 characters with portable components of at most 255 characters.
Byte sizes are bounded by `2^53 - 1` for exact interchange with JavaScript tooling.

Parsing or preview failure leaves the current workflow and session unchanged.

## Open, preview, normalize and apply

These operations intentionally have different semantics:

1. **Preview** parses, validates, resolves installed plugins and computes migrations without changing
   application state.
2. **Normalize / Save As** writes a canonical copy atomically through a sibling partial file. The
   imported source path may not be used as the normalize target.
3. **Apply** is a separate, explicit operation. The server reparses the uploaded bytes, verifies the
   previewed canonical hash through `If-Match`, checks plugin compatibility and atomically protects
   dirty workflow state before replacement.
4. **Execute** is never implied by open, preview, normalize or apply.

Web Apply requires a first confirmation explaining that the workflow is replaced but not executed.
When the server reports `dirty-workflow`, a second confirmation is required before the client sends
`X-Audio-Analyzer-Discard-Dirty: true`. A changed file after preview fails with HTTP 412 rather than
being applied. Weak or malformed hash preconditions are rejected.

The single-user workflow cannot be replaced while a shared collaboration session has active
participants. This avoids creating two canonical workflow states. A retained session with no current
participants does not block import.

The Swing desktop currently offers modeless preview and normalized Save As only; it never applies or
executes a document on open. The web workbench offers preview, normalize and explicitly confirmed
apply.

CLI, desktop and web previews include the complete canonical document, so profiles, assets, output
requests, provenance and preserved plugin sections can be inspected before any replacement.
Save As rejects symbolic-link and hard-link aliases of the imported file. Saving uses a unique
sibling temporary file and removes it on failure; predictable `.partial` paths are never followed.

## Local asset and output bindings

Portable references are deliberately separated from machine-specific paths. `ExperimentLocalBindings`
contains the selected absolute paths only in local application memory and is not part of the JSON
model or codec.

`ExperimentLocalBindingService` performs a non-mutating readiness check:

- every required asset ID must have an explicitly selected local file;
- the selected value must be a regular file;
- byte size must match the portable requirement;
- SHA-256 is calculated as a stream, without loading large assets into memory;
- unused local asset bindings are reported as warnings;
- requested outputs require a separately selected local directory;
- the directory, or its nearest existing ancestor, must be writable;
- validation never creates the output directory or writes an artifact.

Binding readiness also remains false when the document preview itself is incompatible or read-only.
This model is suitable for later Swing/web binding dialogs without leaking absolute paths back into
`.audioexp`.

## Plugin sections

Plugin data is namespaced by the stable `PluginDescriptor.id` and a contribution-local `sectionId`:

```json
"pluginData": {
  "acoustic-localization": {
    "array-localization": {
      "schemaVersion": 2,
      "algorithmVersion": "gcc-phat-workflow/2",
      "data": {}
    }
  }
}
```

`ExperimentDocumentContribution` deliberately separates:

- plugin package version;
- parameter schema version;
- algorithm compatibility identifier;
- local JSON Schema and its digest;
- semantic validation/normalization;
- explicit adjacent migration steps.

Plugins receive only the Jackson-free immutable `DocumentValue` model. The host owns parsing,
limits, canonical serialization and local schema evaluation.

### Missing and incompatible plugins

- Missing **required** plugin/section: preview succeeds for inspection, but execution/application is
  blocked and the document is read-only.
- Missing **optional** plugin/section: canonical data is preserved and a warning is shown.
- Future section schema: data is preserved but not partially interpreted.
- Migration gaps or plugin validator failures: structured JSON-pointer diagnostics block use of the
  affected required section.
- Algorithm mismatch: warning or error according to whether the section is required.

No unsupported section is discarded silently.

Package requirements currently support `*` or an exact installed package version. Other range
expressions fail closed as incompatible. Algorithm incompatibility preserves the original section
and blocks required use even after saving and reopening the normalized document.

Envelope version 1 is the first supported envelope; there is no invented version-0 migration.
Older internal properties manifests are separate formats. Future envelope migrations must be
explicitly specified and tested before their input versions are accepted. Plugin schema migration
is independent of the envelope and advances through explicit adjacent versions. Save As writes a
new document and records successful migrations; it never rewrites the imported source.

## CLI

Build the module and its dependencies with Java 21, then obtain its runtime classpath (Unix shell):

```bash
mvn -B -pl audio-experiment-document -am install
mvn -B -pl audio-experiment-document dependency:build-classpath \
  -Dmdep.outputFile=target/cli-classpath.txt
```

Validate and inspect a document:

```bash
java -cp "audio-experiment-document/target/classes:$(cat audio-experiment-document/target/cli-classpath.txt)" \
  org.hammer.audio.experiment.document.ExperimentDocumentCli \
  validate docs/examples/minimal.audioexp
```

Write a canonical copy to a distinct destination:

```bash
java -cp "audio-experiment-document/target/classes:$(cat audio-experiment-document/target/cli-classpath.txt)" \
  org.hammer.audio.experiment.document.ExperimentDocumentCli \
  normalize docs/examples/minimal.audioexp /tmp/minimal-normalized.audioexp
```

The CLI uses the same codec and plugin-resolution service as application adapters.

## REST API

The Spring workbench exposes these endpoints:

| Method |               Path                |                         Purpose                         |
|--------|-----------------------------------|---------------------------------------------------------|
| `POST` | `/experiment-documents/preview`   | Validate and return a safe summary and diagnostics      |
| `POST` | `/experiment-documents/normalize` | Return canonical `.audioexp` bytes                      |
| `POST` | `/experiment-documents/apply`     | Revalidate and explicitly replace the editable workflow |
| `GET`  | `/experiment-documents/schema`    | Return the bundled local v1 schema                      |

Preview and normalize accept the dedicated media type and `application/json` for tooling
compatibility. Normalize responds with the dedicated media type and an `.audioexp` attachment name.
Errors contain a stable code and JSON Pointer.

Apply additionally requires:

- `If-Match: "<canonical-sha256-from-preview>"`;
- optional `X-Audio-Analyzer-Discard-Dirty: true` only after explicit user confirmation.

A successful Apply response echoes the canonical hash as an ETag and returns the new workflow
projection with `dirty: true`, because the imported workflow has not yet been checkpointed.

## Reference fixtures

- [`minimal.audioexp`](../examples/minimal.audioexp) is a byte-stable core-only setup.
- [`unknown-optional-plugin.audioexp`](../examples/unknown-optional-plugin.audioexp) demonstrates
  preservation of plugin data when the optional plugin is unavailable.
- [`full.audioexp`](../examples/full.audioexp) includes a connected workflow, every core profile,
  calibration validity, provenance versions and two tiny, digest-verified assets in `examples/assets`.
- [`legacy-plugin.audioexp`](../examples/legacy-plugin.audioexp) and
  [`migrated-plugin.audioexp`](../examples/migrated-plugin.audioexp) are the deterministic before/after
  fixtures for the test gain contribution's explicit schema 1 to 2 migration.
- [`invalid`](../examples/invalid) contains future-version, duplicate-key, traversal and active-content
  documents that the importer must reject. Their stale hashes are intentional: structural rejection
  must happen before hash verification.

These examples are intentionally small. They can be validated and normalized by the public codec,
CLI and REST API.

## Known boundaries

The current slice does not yet provide:

- local hardware/device capability binding;
- a finished Swing/web asset-binding dialog;
- desktop operating-system file association;
- selective semantic merge into an existing workflow;
- a complete evidence package/container;
- trusted participant identity and signatures.

Those features must reuse the same service and must not introduce independent parsers or weaker
security rules.
