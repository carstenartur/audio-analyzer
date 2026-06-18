Collaborative Workflow Platform Architecture

1. Zielbild

Audio Analyzer soll langfristig nicht nur eine Swing-basierte Analyseanwendung sein, sondern eine webbasierte, kollaborative Forschungsplattform für akustische Workflows.

Die bestehende Swing-GUI bleibt zunächst erhalten. Die zukünftige GUI-Entwicklung soll jedoch in einer webbasierten Oberfläche stattfinden.

Zentrale Ziele:

* browserbasierter Workflow-Editor
* mehrere Nutzer gleichzeitig auf demselben Arbeitsblatt
* Live-Sichtbarkeit fremder Änderungen
* unbegrenztes Undo/Redo
* typisierte Workflow-Knoten und Ports
* reproduzierbare Ausführung
* JGit-basierte Versionierung
* modellbasierte Konfliktlösung
* Wiederverwendung von Konzepten aus dem Taxonomy-Projekt

2. Zielarchitektur

Browser
  React / React Flow
  Yjs Collaborative Document
  Presence / Cursors / Selection
        │
        ▼
Collaboration Gateway
  WebSocket
  Awareness
  Persistence Adapter
        │
        ▼
Workflow Service
  Validation
  Type Checking
  Execution Planning
  Snapshot Export
        │
        ├──────────────► JGit Repository
        │                   Branches
        │                   Commits
        │                   Model Merge
        │                   History
        │
        ▼
Execution Engine
  Audio Analyzer Core
  Dataset Import
  Feature Extraction
  Classification
  Localization
  Benchmarking
  Reporting

3. Grundprinzip

Der Workflow ist kein UI-Zustand.

Der Workflow ist ein versionierbares Domänenmodell.

Die Weboberfläche editiert dieses Modell. Die Swing-GUI, CLI, REST-API und spätere Scheduler sollen dasselbe Modell nutzen können.

4. Kernmodell

Workflow

Ein Workflow beschreibt ein reproduzierbares Experiment oder eine Analysepipeline.

Workflow
  id
  name
  version
  nodes
  edges
  metadata
  layout
  executionSettings

Node

Ein Node ist ein ausführbarer oder konfigurierender Baustein.

Beispiele:

* HumBugDB Import
* Synthetic Generator
* Bandpass Filter
* FFT
* Feature Extraction
* Classifier
* Localization
* Benchmark
* Report

Node
  id
  type
  label
  position
  inputPorts
  outputPorts
  properties
  executionState

Port

Ports sind typisiert.

Port
  id
  name
  direction
  dataType
  required
  multiplicity

Beispiele für Typen:

Dataset
AudioBlock
Spectrum
FeatureVector
FeatureSet
ClassificationResult
LocalizationResult
BenchmarkResult
Report

Edge

Eine Edge verbindet zwei kompatible Ports.

Edge
  id
  sourceNodeId
  sourcePortId
  targetNodeId
  targetPortId

5. Typsystem

Das Typsystem verhindert ungültige Workflows frühzeitig.

Beispiele:

AudioBlock      → FFT              gültig
FeatureVector   → Classifier       gültig
Dataset         → Localization     ungültig
Report          → BandpassFilter   ungültig

Validation muss sowohl im Browser als auch im Backend möglich sein.

Der Browser darf ungültige Verbindungen bereits visuell verhindern. Das Backend bleibt aber die autoritative Prüfinstanz.

6. Kollaborationsmodell

Während mehrere Nutzer gleichzeitig arbeiten, ist Yjs das primäre Kollaborationsmodell.

Git ist nicht der Mechanismus für Live-Kollaboration.

Live Editing:
Browser ↔ Yjs ↔ WebSocket ↔ andere Browser
Versionierung:
Yjs Snapshot → Workflow Model → JGit Commit

7. Operationen

Alle Änderungen am Workflow sollten als semantische Operationen formulierbar sein.

Beispiele:

CreateNode
DeleteNode
MoveNode
RenameNode
UpdateNodeProperty
CreateEdge
DeleteEdge
UpdatePortType
CreateComment
ResolveConflict

Diese Operationen sind wichtig für:

* Undo/Redo
* Integrationstests
* Audit-Logs
* modellbasierte Merges
* Konfliktanalyse

8. Gleichzeitige Bearbeitung

Typische Szenarien:

Zwei Nutzer ändern verschiedene Nodes

User A ändert FFT.windowSize
User B ändert Classifier.threshold

Erwartung:

Kein Konflikt.

Zwei Nutzer ändern dieselbe Property

User A: FFT.windowSize 2048 → 4096
User B: FFT.windowSize 2048 → 1024

Erwartung:

Semantischer Konflikt.

Nicht als Textkonflikt, sondern als Modellkonflikt anzeigen:

Node: FFT
Property: windowSize
Base: 2048
User A: 4096
User B: 1024

Ein Nutzer löscht einen Node, ein anderer verbindet ihn

User A löscht Node X
User B erstellt Edge von Node X zu Node Y

Erwartung:

Edge wird ungültig oder als Konflikt markiert.

Ein Nutzer ändert Porttyp, ein anderer erstellt Verbindung

User A ändert OutputPort von AudioBlock zu Spectrum
User B verbindet diesen Port mit einem AudioBlock-Eingang

Erwartung:

Backend-Validation markiert Verbindung als ungültig.

9. Undo/Redo

Undo muss nutzerbezogen funktionieren.

User A undo

darf nicht blind Änderungen von User B zurücknehmen.

Yjs UndoManager kann dafür als Grundlage dienen, aber die Semantik muss getestet werden.

Wichtige Fälle:

* lokales Undo während andere weiterarbeiten
* Undo eines gelöschten Nodes
* Undo einer Verbindung zu einem inzwischen geänderten Port
* Redo nach Remote-Änderungen
* Undo nach Commit
* Undo nach Reconnect

10. Presence

Die Weboberfläche soll anzeigen:

* aktive Nutzer
* Cursorposition
* ausgewählte Nodes
* aktuell bearbeitete Properties
* Viewport anderer Nutzer
* optional: Follow-User-Modus

Presence ist nicht Teil des versionierten Workflows.

11. Git/JGit-Versionierung

JGit speichert stabile Workflow-Snapshots.

Git soll leisten:

* Commit
* Branch
* Merge
* Cherry-pick
* Revert
* History
* Vergleich
* Wiederherstellung

Git soll nicht die Live-Kollaboration lösen.

12. Speicherformat

JSON ist als Austauschformat geeignet, aber nicht als naives großes workflow.json.

Empfohlen:

workflow/
  workflow.json
  nodes/
    <node-id>.json
  edges/
    <edge-id>.json
  metadata.json
  layout.json

Vorteile:

* weniger Textkonflikte
* stabilere Diffs
* bessere Reviewbarkeit
* einfachere Teil-Merges

Langfristig sollte dennoch ein modellbasierter Merge verwendet werden.

13. Modellbasierter Merge

Der Merge soll nicht zeilenbasiert gedacht werden.

Stattdessen:

Base Workflow
Branch A Workflow
Branch B Workflow
        ↓
Workflow Model Diff
        ↓
Semantic Merge
        ↓
Merged Workflow / Conflict Report

Konflikte sollen fachlich angezeigt werden:

Node deleted vs Node modified
Port type changed vs Edge created
Property changed differently
Node renamed differently

14. Workflow-Ausführung

Ein Workflow wird immer aus einem stabilen Snapshot ausgeführt.

Current Collaborative State
        ↓
Execution Snapshot
        ↓
Validation
        ↓
Execution Plan
        ↓
Execution
        ↓
Results

Während der Workflow läuft, können Nutzer weiter editieren. Die laufende Ausführung bezieht sich aber auf den Snapshot.

15. Execution State

Nodes können Zustände anzeigen:

Idle
Ready
Invalid
Queued
Running
Completed
Failed
Skipped
Cancelled

Kanten können Datenstatistiken anzeigen:

1452 recordings
44100 Hz
17 features
Accuracy 91.2 %

16. Web-Frontend

Empfohlener Stack:

React
React Flow / xyflow
Yjs
WebSocket Provider
TypeScript

React Flow übernimmt:

* Nodes
* Edges
* Drag & Drop
* Zoom
* Pan
* Custom Node Rendering
* Port Handles

Yjs übernimmt:

* Shared Document
* Collaboration
* Conflict-free updates
* Awareness
* UndoManager

17. Backend

Empfohlener Stack:

Spring Boot
WebSocket
REST API
JGit
Audio Analyzer Engine

Kernendpunkte:

GET    /api/workspaces
POST   /api/workspaces
GET    /api/workflows/{id}
PUT    /api/workflows/{id}
POST   /api/workflows/{id}/validate
POST   /api/workflows/{id}/execute
GET    /api/executions/{id}
GET    /api/executions/{id}/results
POST   /api/workflows/{id}/commit
POST   /api/workflows/{id}/branches
POST   /api/workflows/{id}/merge

18. Integration mit Swing

Die bestehende Swing-GUI bleibt zunächst erhalten.

Sie sollte mittelfristig nicht weiter ausgebaut werden.

Ziel:

Swing GUI
  uses Workflow Service
Web GUI
  uses Workflow Service
CLI
  uses Workflow Service

Keine neue Funktion sollte ausschließlich in Swing entstehen.

19. Teststrategie

Dieses Projekt benötigt ungewöhnlich starke Integrationstests.

Unit Tests

* Workflow validation
* Port compatibility
* Node property validation
* Serialization
* Diff
* Merge
* Execution planning

Collaboration Tests

Simulierte Nutzer:

User A
User B
User C

Szenarien:

* gleichzeitiges Node-Verschieben
* gleichzeitiges Property-Ändern
* Delete-vs-Modify
* Connect-vs-TypeChange
* Undo-vs-RemoteChange
* Offline-vs-Online-Reconnect
* Commit während andere editieren

Fuzz Tests

Zufällige Operationen:

create node
move node
delete node
connect
disconnect
update property
undo
redo
commit
merge

Nach jeder Operation:

* Workflow bleibt syntaktisch gültig
* ungültige Edges werden erkannt
* keine verlorenen Nodes
* keine Kanten auf nicht existierende Ports
* keine doppelten IDs
* keine nicht reproduzierbaren Ergebnisse

End-to-End Tests

Browser-basierte Tests mit Playwright:

* zwei Browser öffnen dasselbe Workflow-Blatt
* User A bewegt Node
* User B sieht Bewegung
* User B ändert Property
* User A sieht Änderung
* User A undo
* User B sieht konsistenten Zustand
* Workflow wird gespeichert
* Backend führt gespeicherten Snapshot aus

20. Umsetzung in Issues

Issue 1: Define workflow domain model and JSON schema

Deliverables:

* Workflow
* Node
* Port
* Edge
* Property
* Artifact
* JSON schema
* validation tests

Issue 2: Add workflow validation and typed port compatibility

Deliverables:

* WorkflowValidator
* TypeRegistry
* PortCompatibilityService
* invalid edge reporting

Issue 3: Add workflow execution snapshot model

Deliverables:

* ExecutionSnapshot
* ExecutionPlan
* ExecutionResult
* immutable execution input

Issue 4: Add REST API for workflow CRUD and validation

Deliverables:

* workflow endpoints
* validation endpoint
* JSON import/export

Issue 5: Add React/React Flow prototype

Deliverables:

* web module
* workflow canvas
* custom nodes
* ports
* edges
* property panel

Issue 6: Add Yjs collaboration layer

Deliverables:

* shared workflow document
* WebSocket sync
* multi-user editing
* presence
* basic undo/redo

Issue 7: Add collaborative editing integration tests

Deliverables:

* multi-client tests
* concurrent edit tests
* undo/redo tests
* reconnect tests

Issue 8: Add JGit workflow persistence

Deliverables:

* commit workflow snapshot
* branch workflows
* load history
* compare snapshots

Issue 9: Add model-based diff and merge

Deliverables:

* WorkflowDiff
* WorkflowMerge
* conflict model
* semantic conflict reports

Issue 10: Add workflow execution from web UI

Deliverables:

* execute button
* execution status
* node state updates
* result artifacts

Issue 11: Add benchmark/result annotations to graph

Deliverables:

* node result badges
* edge statistics
* benchmark overlays
* error display

Issue 12: Migrate existing acoustic workflows to graph nodes

Deliverables:

* DatasetImportNode
* SyntheticGeneratorNode
* FeatureExtractionNode
* ClassifierNode
* LocalizationNode
* BenchmarkNode
* ReportNode

21. Recommended implementation order

Do not start with React.

Start with the model.

Recommended order:

1. Workflow domain model
2. Validation and type system
3. Execution snapshot
4. REST API
5. React Flow prototype
6. Yjs collaboration
7. Integration tests
8. JGit persistence
9. Model merge
10. Execution from web
11. Result overlays
12. Acoustic node library

22. Architectural decision

The browser UI is the future primary GUI.

The Swing UI remains supported temporarily but should not receive major new workflow-editing features.

The long-term product is a collaborative, browser-based workflow platform with a Java execution backend.

23. Open questions

* Should the collaboration server store Yjs updates permanently or only snapshots?
* Should Git commits be user-triggered or automatic?
* Should every workflow edit become an operation log entry?
* Should workflow execution require a committed snapshot?
* Should comments be versioned or only collaborative UI state?
* Should layout be versioned together with semantics?
* Should unresolved merge conflicts be represented as first-class workflow objects?
* Can Taxonomy’s model-merge logic be extracted into a shared library?

24. First milestone

A realistic first milestone is:

Collaborative Workflow Prototype

Scope:

* browser canvas
* typed nodes
* typed edges
* two users editing simultaneously
* live presence
* undo/redo
* save/load workflow
* backend validation

No audio execution yet.

This milestone proves the risky part first: collaborative editing of a typed workflow graph.