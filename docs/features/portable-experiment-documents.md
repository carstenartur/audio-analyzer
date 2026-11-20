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

Recordings use the binary `.aarec` format. A separate ZIP evidence export can bundle the canonical
document, explicitly selected recordings/datasets and available results. The `.audioexp` setup remains
small; binary data is never base64-embedded in it.

## Determinism

The codec reparses and serializes the embedded workflow with `WorkflowDslParser` and
`WorkflowDslSerializer`. Object keys, plugin IDs, section IDs, assets and outputs are emitted in a
deterministic order. Equivalent decimal plugin values are normalized. Saving the same normalized
setup twice produces byte-identical UTF-8 JSON.

The preview exposes three complementary hashes:

- `workflow.sha256` protects the canonical workflow DSL;
- `provenance.canonicalSha256` protects the complete normalized document while excluding the hash
  field itself from the hash input;
- each namespaced section hash covers its schema version, algorithm identifier and canonical data.

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

## Open, import, edit, Save and Save As

All surfaces share `ExperimentDocumentService`, and editable sessions retain the complete document.
Changing the workflow preserves profiles, plugin sections, asset requirements, output requests and
provenance. An incomplete desktop workflow draft remains dirty until it validates or is explicitly
discarded; Save never substitutes an earlier valid workflow for an invalid current draft.

|        Operation        |                                                                          Behavior                                                                          |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Preview / Validate      | Parse, validate, compare, resolve installed sections and compute explicit migrations without replacing the current setup.                                  |
| Open                    | Show complete JSON, compatibility, local requirements and a semantic workflow diff, then require confirmation before replacing the current editable setup. |
| Import independent copy | Preserve the complete incoming setup separately. Existing edits are retained; unknown required sections can be copied for inspection.                      |
| Save                    | Save the selected local copy, or download the complete current browser document. A first desktop save requires a Save As destination.                      |
| Save As / Normalize     | Write or download a canonical copy. Desktop and CLI reject the imported original and its symbolic/hard-link aliases as destinations.                       |
| Execute                 | A separate explicit workflow operation; none of the document actions starts it.                                                                            |

Dirty replacement offers **Save current and open**, **Discard current changes and open**, and
**Cancel** together. Save failure or cancellation leaves the current setup available. Browser copies
are held in the local server workspace (maximum 32); download them before ending that process. The
browser file picker/download flow does not grant the server write access to the user's computer.
Starting a browser download leaves the document dirty. The panel asks the user to confirm that the
copy was saved before acknowledging its exact hash or continuing a pending Open. Cancelling that
confirmation preserves the current document and unsaved state, including blocked/cancelled downloads.
The desktop recent-file menu retains ten paths in memory; browser recent entries are filename hints
and still require a deliberate file selection.

The confirmation includes semantic node, edge, field and parameter differences from the existing
`WorkflowDiff` model. It offers replacement or an independent copy; it does not silently combine
conflicting workflows. Selective merging remains available through the separate workflow history
facilities, rather than through an invented JSON text merge.

Browser Open sends both the candidate document hash (`If-Match`) and the current graph hash
(`X-Audio-Analyzer-Current-Workflow`). A file or current graph changed after preview receives HTTP 412
and must be previewed again. Desktop modeless previews/editors use the session revision for the same
stale-state protection. Explicit discard never overrides a stale preview. Weak or malformed hash
preconditions are rejected. An acknowledged download clears document dirty status only for the exact
snapshot downloaded; a later edit restores it.

The single-user workflow cannot be replaced while a shared collaboration session has active
participants. A retained session with no current participants does not block import.

Desktop and browser actions include keyboard Open/Save/Save As, complete copyable JSON, validation
messages, plugin parameter tables, hashes, source mode, profiles and local requirements. The browser
also accepts drag/drop uploads. Desktop command-line arguments and supported already-running open
file events enter the same non-mutating preview and queue while another document operation runs.

### Atomic save and cancellation

Desktop/CLI writes use a unique sibling temporary file followed by an atomic replacement. If the
filesystem cannot perform that atomic move, the save fails and retains the destination; it does not
fall back to an ordinary replacement. Failed writes remove the temporary file. The original import
is protected even after a different copy has been saved.

Parsing, validation and file work run in background Swing tasks. Cancel requests interruption and
keeps the task active until its code actually finishes. Hashing and file writes check interruption,
including immediately before atomic replacement. A request racing a completed replacement reports
the actual successful result. Browser cancellation is available for inspection, normalization and
resource/evidence reads; mutating Open/import/save acknowledgement completes before another action
is offered.

## Explicit local resources

`ExperimentLocalBindings` is local application state; it is never written into `.audioexp`. Swing
uses file/directory choosers and a discovered device selector. Browser fields explicitly refer to
paths on the **machine running Audio Analyzer**, which may differ from the browser's machine. No
relative asset reference is followed until the user selects its local binding.

HTTP access additionally requires **host authorization**. The default permits no local file or
output-directory access from a browser/API request. A host owner can approve directories at launch:

```bash
java -Daudio.experiment-document.local-roots=/srv/audio/shared:/srv/audio/results \
  -jar audio-app/target/audio-app-0.1.0-SNAPSHOT-web.jar
```

Use the operating system's path-list separator (`:` on Linux/macOS, `;` on Windows). Each configured
root must exist and be a directory. Both `/bindings` and `/evidence` reject unapproved paths with HTTP
403 **before** checking existence, size, digest or writability; canonical resolution rejects symlink
escapes. Only descendants of host-approved roots can be read, including requested result files.
Approval belongs to host startup configuration, not to document fields, client checkboxes or a prior
client request. Desktop file choosers are a local user action and do not require this web-only policy.

`ExperimentLocalBindingService` checks:

- each required asset has a selected regular file;
- byte size matches before hashing, then streaming SHA-256 matches;
- recognized AAR1/AAR2, WAVE, FLAC, Ogg and JSON signatures match the declared media type; an
  unrecognized format requires explicit local media-type confirmation;
- each requested output has a separately selected writable directory or writable existing ancestor;
- selected hardware advertises the required PCM sample rate, sample width, signedness, byte order
  and capture channel count, without acquiring a line or opening the device;
- logical channels have a unique, explicit physical-channel mapping and required synchronization
  is confirmed;
- calibration has not expired and its applicability to a selected device/mapping is confirmed.

Missing, incompatible or expired resources produce JSON-pointer diagnostics and leave inspection
available. They never cause silent simulation, calibration or device substitution. A workflow saved
without an imported envelope is exported with source mode `unbound`; source requirements must be
configured before execution. These checks describe readiness, not a hardware capture test.

### Separate evidence export

**Export evidence package** writes an `application/zip` download or an explicitly chosen local ZIP.
It contains `experiment.audioexp`, declared assets at their validated relative paths, available
requested output files under `results/`, and `evidence-manifest.json`. The manifest records the
canonical document identity, evidence SHA-256 values, byte sizes/media types and unavailable output
IDs. It contains no selected absolute paths or device identities.

Export supports at most 64 assets and 2 GiB of streamed content. Assets must pass size, digest and
media checks; a second streamed digest check rejects files changed during packaging. Reserved or
colliding entry names are rejected. The local ZIP cannot replace the imported document, selected
assets or selected result files. Failure leaves an existing destination intact. Historical or
unsupported plugin/calibration data remains exportable for inspection; packaging evidence does not
assert execution readiness. This ZIP is a separate export format, not an automatically imported
archive and not a replacement for the portable `.audioexp` contract.

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

Package requirements support `*`, an exact package version, or numeric Maven-style intervals such
as `[1.0,2.0)`, `(,2.0]` and `[1.0,)`. Exact prerelease identifiers are allowed; interval matching does
not guess prerelease ordering. Unsupported expressions fail closed as incompatible. Algorithm incompatibility preserves the original section
and blocks required use even after saving and reopening the normalized document.

Envelope version 1 is the first supported envelope; there is no invented version-0 migration.
Older internal properties manifests are separate formats. Future envelope migrations must be
explicitly specified and tested before their input versions are accepted. Plugin schema migration
is independent of the envelope and advances through explicit adjacent versions. Save As writes a
new document and records successful migrations; it never rewrites the imported source.

### Plugin-author contract and fixture kit

The API remains independent of Jackson, Swing, Spring and JavaSound. Implement
`AudioAnalyzerPlugin.experimentDocumentContributions()` using immutable `DocumentValue` values and
stable plugin/section identifiers. Duplicate plugin IDs or duplicate section IDs within one plugin
are rejected during loading. The same section ID in two plugin namespaces is valid.

The copyable [`GainDocumentContribution`](../../audio-experiment-document/src/main/java/org/hammer/audio/experiment/document/examples/GainDocumentContribution.java)
and its [bundled schema](../../audio-experiment-document/src/main/resources/schemas/plugins/example-gain-v2.schema.json)
show a schema 1 → 2 migration from the documented `gainLinear` alias to `gain`, exact numeric data,
a stable algorithm identifier and a semantic diagnostic at `/gain`. The host scopes that pointer to
`/pluginData/<plugin-id>/<section-id>/data/gain`.

Use `PluginDocumentTestKit.schemaSha256(schemaJson)` when generating the constant bundled schema
digest. It hashes canonical schema JSON, so whitespace and object key order do not change identity.
`parameters(schemaJson)` produces the parameter table used by import previews. In a host-side test,
call `verifyFixture(service, legacyBytes, expectedCanonicalBytes)` to require byte-exact expected
output, repeatable normalization, normalization idempotence and no blocking compatibility errors.
Keep those tests separate from the plugin runtime artifact so its API dependencies stay minimal.
`sectionSha256(section)` includes schema/algorithm identity along with parameter values.

Plugin schemas deliberately use a bounded local subset: `type`, `required`, `properties`, boolean
`additionalProperties`, `enum`, `minimum`, `maximum`, `minLength`, `maxLength`, `minItems`, `maxItems`,
and descriptive `$id`, `$schema`, `title`, `description`. Unsupported keywords and every `$ref` are
rejected, even inside properties absent from the current value. `integer` rejects fractional
numbers, string limits count Unicode code points, and enum comparisons preserve exact numeric
semantics. This subset is separate from the complete bundled schema used for the outer envelope.

Each registration and section normalization runs with a one-second deadline. A shared pool permits
at most four active callbacks and has no pending work queue; plugins ignoring interruption cannot
create an unbounded number of host workers. Preview stops scheduling sections after its five-second
budget and interprets at most 64 sections. Schema documents are limited to 256 KiB; generated section
values to 1 MiB, bounded depth and 10,000 entries per collection. Plugin diagnostics are limited to
128 entries with bounded pointers, codes and messages. Saturation, cancellation and timeout become
explicit diagnostics. Installed plugin code is trusted code; these controls are fault containment,
not an operating-system security sandbox.

Migration chains must contain explicit adjacent version steps. A gap, thrown exception, null result,
limit violation, invalid final schema or semantic error preserves the original section and records no
successful migration for it. The host checks the final migrated value against the current schema
before semantic normalization, then bounds and validates the normalized result again. Validators
must be deterministic, side-effect-free and idempotent. They must not use clocks, randomness, local
resources or network reads to choose defaults. Successful migrations are recorded in provenance;
package, schema and algorithm versions remain independent.

## CLI

Build the module and its dependencies with Java 21, then obtain its runtime classpath (Unix shell):

```bash
mvn -B -pl audio-experiment-document -am install
mvn -B -pl audio-experiment-document dependency:build-classpath \
  -Dmdep.outputFile=target/cli-classpath.txt
```

Validate and inspect a document (`inspect` is an explicit alias for this output):

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

The Spring workbench exposes these endpoints (all document parsing uses the shared service):

|      Method      |                Path                 |                                                Purpose                                                 |
|------------------|-------------------------------------|--------------------------------------------------------------------------------------------------------|
| `POST`           | `/experiment-documents/preview`     | Validate, return full canonical JSON, hashes, installed schemas, local requirements and semantic diff. |
| `POST`           | `/experiment-documents/normalize`   | Return canonical `.audioexp` bytes.                                                                    |
| `POST`           | `/experiment-documents/apply`       | Confirm and replace the complete editable setup and workflow.                                          |
| `GET`            | `/experiment-documents/current`     | Download the complete current setup with edits retained.                                               |
| `GET`            | `/experiment-documents/session`     | Current document hash, dirty state and last successful save/download time.                             |
| `POST`           | `/experiment-documents/saved`       | Acknowledge the exact downloaded snapshot using `If-Match`.                                            |
| `POST`           | `/experiment-documents/import`      | Retain an independent complete copy, using preview `If-Match`.                                         |
| `GET`            | `/experiment-documents/copies`      | List retained workspace copies.                                                                        |
| `GET` / `DELETE` | `/experiment-documents/copies/{id}` | Download or explicitly discard one independent copy.                                                   |
| `POST`           | `/experiment-documents/bindings`    | Verify explicitly selected local resources without opening devices.                                    |
| `POST`           | `/experiment-documents/evidence`    | Stream a separate evidence ZIP without creating server-side output files.                              |
| `GET`            | `/experiment-documents/schema`      | Return the bundled local v1 schema.                                                                    |

Preview, normalize, import and apply accept the dedicated media type or `application/json`.
Downloads use the vendor media type and fixed safe attachment names. Errors include a stable code and
JSON Pointer. Apply requires `If-Match` and `X-Audio-Analyzer-Current-Workflow`; a deliberate dirty
replacement additionally sends `X-Audio-Analyzer-Discard-Dirty: true`. The successful response echoes
the canonical hash in its ETag and returns the imported projection with `dirty: true`.

Bindings/evidence accept a bounded JSON wrapper containing `document` (canonical JSON text),
`assetPaths`, `outputDirectory`, `deviceId`, `deviceChannels`, `channelMapping`,
`synchronizationConfirmed`, `calibrationConfirmed` and `confirmedMediaTypes`. Its duplicate keys,
trailing JSON, excessive nesting and oversized strings/collections are rejected. The wrapper is
local input and is never embedded into an exported setup.

## Desktop distribution

The [desktop packaging guide](../../packaging/desktop/README.md) describes reproducible Java 21
`jpackage` app images and native `.audioexp` associations, icons, launcher behavior and smoke tests.
The Linux packaging CI builds and extracts a real Debian package to verify the association, MIME
handler, argument placeholder and icon without installing it. Windows/macOS packages must be built
and their file-manager association tested on those target platforms. An unpackaged app image does
not install a system association.

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

- Import offers explicit replacement or a complete independent copy. Selective workflow merge uses
  the established workflow history/merge facilities; it is not exposed as a document import mode.
- Local readiness uses advertised JavaSound capabilities and explicit mapping/calibration
  confirmations. A physical device capture/measurement remains a separate action.
- Native Windows/macOS installation and file-manager behavior require platform smoke tests.

Portable provenance is an imported claim and integrity record; cryptographic document signatures
are outside this format version.
