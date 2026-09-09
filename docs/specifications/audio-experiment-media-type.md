# Audio Analyzer experiment media-type registration dossier

Status: proposed vendor-tree registration information for envelope version 1. No registration has
been submitted by this implementation, and the media type must not be advertised as IANA-registered.
The maintainer must confirm the final subtype, extension and a public registration contact before
submission. This dossier accompanies the [format specification](../features/portable-experiment-documents.md).

The registration procedure and template are described in
[RFC 6838](https://www.rfc-editor.org/rfc/rfc6838), with the structured JSON suffix described in
[RFC 6839](https://www.rfc-editor.org/rfc/rfc6839).

|   Registration field    |                                                  Proposed value                                                  |
|-------------------------|------------------------------------------------------------------------------------------------------------------|
| Type name               | `application`                                                                                                    |
| Subtype name            | `vnd.carstenartur.audio-analyzer.experiment+json`                                                                |
| Required parameters     | None                                                                                                             |
| Optional parameters     | None; encoding is UTF-8                                                                                          |
| Encoding considerations | Binary MIME transfer category; content is UTF-8 JSON                                                             |
| Intended usage          | COMMON                                                                                                           |
| Applications            | Audio Analyzer desktop, web workbench, CLI and independent experiment tools                                      |
| File extension          | `.audioexp`                                                                                                      |
| Magic number            | None; parsers verify the `format` and `formatVersion` members                                                    |
| Macintosh type code     | None                                                                                                             |
| Deprecated aliases      | None                                                                                                             |
| Change controller       | Audio Analyzer maintainer, GitHub account `carstenartur`                                                         |
| Author/contact          | [Project maintainer](https://github.com/carstenartur); submission contact email to be supplied by the maintainer |
| Restrictions on usage   | None                                                                                                             |

## Published specification and schema

- [Portable experiment document specification](https://github.com/carstenartur/audio-analyzer/blob/master/docs/features/portable-experiment-documents.md)
- [Version 1 JSON Schema](https://raw.githubusercontent.com/carstenartur/audio-analyzer/master/audio-experiment-document/src/main/resources/schemas/audio-analyzer-experiment-v1.schema.json)
- [Conformance fixtures](https://github.com/carstenartur/audio-analyzer/tree/master/docs/examples)

The format identifier is `io.github.carstenartur.audio-analyzer.experiment`. Version 1 describes a
setup and reproducibility contract. Audio and large evidence files are separate digest-addressed
assets; this subtype does not identify a recording or archive container.

## Interoperability

Ordinary JSON processing semantics are retained. A generic JSON tool can parse the document without
understanding Audio Analyzer; domain validation additionally requires the envelope, canonical DSL,
schema, cross-reference and digest rules. HTTP tooling may send `application/json`, but normalized
responses use the dedicated subtype. Envelope, workflow and plugin schema/algorithm versions are
independent. Unsupported envelope versions are rejected. Unknown optional plugin sections are
preserved without loading implementations. The bounded canonical normal form and fixtures are part
of the interoperability contract.

## Security considerations

The document is data, not executable content. Import does not resolve class names, run commands,
install plugins, fetch URLs, bind devices or write outputs. Importers enforce byte, depth, token,
string and collection limits, reject duplicate keys and unknown core fields, validate workflow
structure, and verify canonical hashes. The schema is bundled; identifiers in documents do not
trigger remote schema retrieval. Plugin payloads are inspected only through installed, trusted
contributions and retain independent compatibility checks.

Portable paths reject traversal, absolute paths, reserved device names and invalid components.
Assets are opened only after explicit local selection; requested outputs are bound to a separately
selected destination. Preview precedes workflow replacement. Hardware binding requires explicit
consent and is not performed by the document service. Digests detect changes but do not authenticate
the sender. Display identities and account references must not establish trust. Importers must
render text as text, including unknown plugin content.

## Fragment-identifier policy

Version 1 defines no subtype-specific fragment identifiers. A fragment does not select an experiment
operation, authorize execution or change import semantics. Generic `+json` processing remains
subject to the suffix registration; no additional fragment interpretation is introduced here.

## Change and release policy

Review the proposed identity and provide the registration contact before declaring it stable.
Retain versioned schema URLs and fixtures. Incompatible envelope changes require a new envelope
version with an explicit migration contract. An eventual evidence container requires its own
specification and media type. Registration submission is a separate maintainer action.
