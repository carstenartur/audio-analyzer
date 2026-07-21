package org.hammer.audio.workflow.merge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowSemanticValueFormatter;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ConflictKind;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ElementKind;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Preview;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Resolution;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ResolutionChoice;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Result;

/** Deterministic semantic three-way merger over immutable workflow values. */
public final class WorkflowThreeWayMerger {

  private static final Set<ResolutionChoice> OBJECT_CHOICES =
      Set.copyOf(
          EnumSet.of(
              ResolutionChoice.BASE,
              ResolutionChoice.LOCAL,
              ResolutionChoice.REMOTE,
              ResolutionChoice.DELETE));
  private static final Set<ResolutionChoice> TYPED_VALUE_CHOICES =
      Set.copyOf(
          EnumSet.of(ResolutionChoice.BASE, ResolutionChoice.LOCAL, ResolutionChoice.REMOTE));
  private static final Set<ResolutionChoice> CUSTOM_STRING_CHOICES =
      Set.copyOf(
          EnumSet.of(
              ResolutionChoice.BASE,
              ResolutionChoice.LOCAL,
              ResolutionChoice.REMOTE,
              ResolutionChoice.CUSTOM));
  private static final Set<ResolutionChoice> OPTIONAL_STRING_CHOICES =
      Set.copyOf(
          EnumSet.of(
              ResolutionChoice.BASE,
              ResolutionChoice.LOCAL,
              ResolutionChoice.REMOTE,
              ResolutionChoice.DELETE,
              ResolutionChoice.CUSTOM));

  private final WorkflowValidator validator;

  /** Creates a merger using the production workflow validator. */
  public WorkflowThreeWayMerger() {
    this(new WorkflowValidator());
  }

  /** Creates a merger using an explicit validator. */
  public WorkflowThreeWayMerger(WorkflowValidator validator) {
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  /** Calculates all automatic decisions and ordered unresolved semantic conflicts. */
  public Preview preview(Workflow base, Workflow local, Workflow remote) {
    Computation computation = compute(base, local, remote);
    Workflow workflow = computation.draft().workflow();
    return new Preview(
        workflow,
        computation.pending().stream().map(PendingConflict::conflict).toList(),
        validator.validate(workflow));
  }

  /** Applies explicit resolutions and returns a validated deterministic candidate. */
  public Result resolve(
      Workflow base, Workflow local, Workflow remote, List<Resolution> resolutions) {
    Objects.requireNonNull(resolutions, "resolutions");
    Computation computation = compute(base, local, remote);
    Map<String, Resolution> indexedResolutions = indexResolutions(resolutions);
    Set<String> knownConflictIds =
        computation.pending().stream()
            .map(pending -> pending.conflict().conflictId())
            .collect(Collectors.toUnmodifiableSet());
    for (String conflictId : indexedResolutions.keySet()) {
      if (!knownConflictIds.contains(conflictId)) {
        throw new IllegalArgumentException("Unknown workflow merge conflict: " + conflictId);
      }
    }

    List<Conflict> unresolved = new ArrayList<>();
    for (PendingConflict pending : computation.pending()) {
      Resolution resolution = indexedResolutions.get(pending.conflict().conflictId());
      if (resolution == null) {
        unresolved.add(pending.conflict());
      } else {
        pending.apply(resolution);
      }
    }

    Workflow workflow = computation.draft().workflow();
    return new Result(workflow, unresolved, validator.validate(workflow));
  }

  private Computation compute(Workflow base, Workflow local, Workflow remote) {
    requireCompatibleInputs(base, local, remote);
    WorkflowMergeDraft draft = WorkflowMergeDraft.from(base);
    List<PendingConflict> pending = new ArrayList<>();

    mergeStringValue(
        pending,
        location(ConflictKind.DIVERGENT_VALUE, ElementKind.WORKFLOW, base.id(), "name"),
        values(base.name(), local.name(), remote.name()),
        false,
        draft::setWorkflowName);
    mergeMetadata(
        pending,
        ElementKind.WORKFLOW,
        base.id(),
        base.metadata(),
        local.metadata(),
        remote.metadata(),
        draft::setWorkflowMetadata);

    Map<String, Node> baseNodes = indexNodes(base.nodes());
    Map<String, Node> localNodes = indexNodes(local.nodes());
    Map<String, Node> remoteNodes = indexNodes(remote.nodes());
    Map<String, Edge> baseEdges = indexEdges(base.edges());
    Map<String, Edge> localEdges = indexEdges(local.edges());
    Map<String, Edge> remoteEdges = indexEdges(remote.edges());

    SpecialNodes specialNodes =
        findSpecialNodes(
            baseNodes, localNodes, remoteNodes, base.edges(), local.edges(), remote.edges());
    installSpecialNodeConflicts(draft, pending, specialNodes);
    mergeNodes(draft, pending, baseNodes, localNodes, remoteNodes, specialNodes.nodeIds());
    mergeEdges(draft, pending, baseEdges, localEdges, remoteEdges, specialNodes.blockedEdgeIds());

    pending.sort(java.util.Comparator.comparing(PendingConflict::conflict, Conflict.ORDERING));
    return new Computation(draft, pending);
  }

  private void requireCompatibleInputs(Workflow base, Workflow local, Workflow remote) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(local, "local");
    Objects.requireNonNull(remote, "remote");
    if (!base.id().equals(local.id()) || !base.id().equals(remote.id())) {
      throw new IllegalArgumentException(
          "Three-way merge requires one stable workflow id, found "
              + base.id()
              + ", "
              + local.id()
              + " and "
              + remote.id());
    }
    requireValid("base", base);
    requireValid("local", local);
    requireValid("remote", remote);
  }

  private void requireValid(String role, Workflow workflow) {
    List<String> violations = validator.validate(workflow);
    if (!violations.isEmpty()) {
      throw new IllegalArgumentException(
          role + " workflow is structurally invalid: " + String.join("; ", violations));
    }
  }

  private static SpecialNodes findSpecialNodes(
      Map<String, Node> baseNodes,
      Map<String, Node> localNodes,
      Map<String, Node> remoteNodes,
      List<Edge> baseEdges,
      List<Edge> localEdges,
      List<Edge> remoteEdges) {
    Map<String, SpecialNode> specialById = new TreeMap<>();
    Set<String> blockedEdgeIds = new TreeSet<>();
    for (String nodeId : union(baseNodes, localNodes, remoteNodes)) {
      Node base = baseNodes.get(nodeId);
      Node local = localNodes.get(nodeId);
      Node remote = remoteNodes.get(nodeId);
      MergeLocation location =
          specialNodeLocation(nodeId, base, local, remote, baseEdges, localEdges, remoteEdges);
      if (location == null) {
        continue;
      }

      List<Edge> baseConnected = WorkflowMergeDraft.connectedEdges(baseEdges, nodeId);
      List<Edge> localConnected = WorkflowMergeDraft.connectedEdges(localEdges, nodeId);
      List<Edge> remoteConnected = WorkflowMergeDraft.connectedEdges(remoteEdges, nodeId);
      Set<String> relatedEdgeIds = new TreeSet<>();
      addEdgeIds(relatedEdgeIds, baseConnected);
      addEdgeIds(relatedEdgeIds, localConnected);
      addEdgeIds(relatedEdgeIds, remoteConnected);
      blockedEdgeIds.addAll(relatedEdgeIds);
      specialById.put(
          nodeId,
          new SpecialNode(
              location,
              base,
              local,
              remote,
              baseConnected,
              localConnected,
              remoteConnected,
              relatedEdgeIds));
    }
    return new SpecialNodes(specialById, blockedEdgeIds);
  }

  private static MergeLocation specialNodeLocation(
      String nodeId,
      Node base,
      Node local,
      Node remote,
      List<Edge> baseEdges,
      List<Edge> localEdges,
      List<Edge> remoteEdges) {
    if (base == null && local != null && remote != null && !local.equals(remote)) {
      return location(ConflictKind.STABLE_ID_COLLISION, ElementKind.NODE, nodeId, "$object");
    }
    if (base != null && local == null && remote != null) {
      if (connectionsChanged(baseEdges, remoteEdges, nodeId)) {
        return location(ConflictKind.DELETE_CONNECT, ElementKind.NODE, nodeId, "connections");
      }
      if (!base.equals(remote)) {
        return location(ConflictKind.DELETE_MODIFY, ElementKind.NODE, nodeId, "$object");
      }
    }
    if (base != null && remote == null && local != null) {
      if (connectionsChanged(baseEdges, localEdges, nodeId)) {
        return location(ConflictKind.DELETE_CONNECT, ElementKind.NODE, nodeId, "connections");
      }
      if (!base.equals(local)) {
        return location(ConflictKind.DELETE_MODIFY, ElementKind.NODE, nodeId, "$object");
      }
    }
    return null;
  }

  private static void installSpecialNodeConflicts(
      WorkflowMergeDraft draft, List<PendingConflict> pending, SpecialNodes specialNodes) {
    for (Map.Entry<String, SpecialNode> entry : specialNodes.byId().entrySet()) {
      String nodeId = entry.getKey();
      SpecialNode special = entry.getValue();
      draft.replaceNodeNeighborhood(
          nodeId, special.base(), special.baseEdges(), special.relatedEdgeIds());
      MergeValues<String> evidence =
          values(
              WorkflowSemanticValueFormatter.neighborhood(special.base(), special.baseEdges()),
              WorkflowSemanticValueFormatter.neighborhood(special.local(), special.localEdges()),
              WorkflowSemanticValueFormatter.neighborhood(special.remote(), special.remoteEdges()));
      Conflict conflict = conflict(special.location(), evidence, OBJECT_CHOICES);
      pending.add(
          new PendingConflict(
              conflict,
              resolution ->
                  applyNeighborhoodResolution(draft, nodeId, special, resolution.choice())));
    }
  }

  private static void applyNeighborhoodResolution(
      WorkflowMergeDraft draft, String nodeId, SpecialNode special, ResolutionChoice choice) {
    switch (choice) {
      case BASE ->
          draft.replaceNodeNeighborhood(
              nodeId, special.base(), special.baseEdges(), special.relatedEdgeIds());
      case LOCAL ->
          draft.replaceNodeNeighborhood(
              nodeId, special.local(), special.localEdges(), special.relatedEdgeIds());
      case REMOTE ->
          draft.replaceNodeNeighborhood(
              nodeId, special.remote(), special.remoteEdges(), special.relatedEdgeIds());
      case DELETE ->
          draft.replaceNodeNeighborhood(nodeId, null, List.of(), special.relatedEdgeIds());
      case CUSTOM -> throw unsupportedChoice(choice, special.location().fieldPath());
    }
  }

  private static void mergeNodes(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      Map<String, Node> baseNodes,
      Map<String, Node> localNodes,
      Map<String, Node> remoteNodes,
      Set<String> specialNodeIds) {
    for (String nodeId : union(baseNodes, localNodes, remoteNodes)) {
      if (specialNodeIds.contains(nodeId)) {
        continue;
      }
      MergeValues<Node> nodeValues =
          values(baseNodes.get(nodeId), localNodes.get(nodeId), remoteNodes.get(nodeId));
      if (nodeValues.base() == null) {
        mergeAddedNode(draft, pending, nodeId, nodeValues);
      } else if (nodeValues.local() == null || nodeValues.remote() == null) {
        mergeDeletedNode(draft, pending, nodeId, nodeValues);
      } else {
        draft.putNode(nodeValues.base());
        mergeNodeFields(draft, pending, nodeValues);
      }
    }
  }

  private static void mergeAddedNode(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String nodeId,
      MergeValues<Node> nodeValues) {
    if (nodeValues.local() == null) {
      draft.putNode(nodeValues.remote());
    } else if (nodeValues.remote() == null || nodeValues.local().equals(nodeValues.remote())) {
      draft.putNode(nodeValues.local());
    } else {
      addNodeObjectConflict(
          draft,
          pending,
          location(ConflictKind.STABLE_ID_COLLISION, ElementKind.NODE, nodeId, "$object"),
          nodeValues);
    }
  }

  private static void mergeDeletedNode(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String nodeId,
      MergeValues<Node> nodeValues) {
    if (nodeValues.local() == null && nodeValues.remote() == null) {
      draft.removeNode(nodeId);
    } else if (nodeValues.local() == null && nodeValues.base().equals(nodeValues.remote())) {
      draft.removeNode(nodeId);
    } else if (nodeValues.remote() == null && nodeValues.base().equals(nodeValues.local())) {
      draft.removeNode(nodeId);
    } else {
      addNodeObjectConflict(
          draft,
          pending,
          location(ConflictKind.DELETE_MODIFY, ElementKind.NODE, nodeId, "$object"),
          nodeValues);
    }
  }

  private static void addNodeObjectConflict(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      MergeLocation location,
      MergeValues<Node> nodeValues) {
    if (nodeValues.base() == null) {
      draft.removeNode(location.elementId());
    } else {
      draft.putNode(nodeValues.base());
    }
    Conflict conflict =
        conflict(
            location, formatted(nodeValues, WorkflowSemanticValueFormatter::node), OBJECT_CHOICES);
    pending.add(
        new PendingConflict(
            conflict,
            resolution ->
                applyNodeObjectResolution(draft, location, nodeValues, resolution.choice())));
  }

  private static void applyNodeObjectResolution(
      WorkflowMergeDraft draft,
      MergeLocation location,
      MergeValues<Node> nodeValues,
      ResolutionChoice choice) {
    Node selected = selectObject(nodeValues, location, choice);
    if (selected == null) {
      draft.removeNode(location.elementId());
    } else {
      draft.putNode(selected);
    }
  }

  private static void mergeNodeFields(
      WorkflowMergeDraft draft, List<PendingConflict> pending, MergeValues<Node> nodeValues) {
    String nodeId = nodeValues.base().id();
    mergeTypedValue(
        pending,
        location(ConflictKind.DIVERGENT_VALUE, ElementKind.NODE, nodeId, "type"),
        mapped(nodeValues, Node::type),
        Function.identity(),
        value -> draft.setNodeType(nodeId, value));
    mergeStringValue(
        pending,
        location(ConflictKind.DIVERGENT_VALUE, ElementKind.NODE, nodeId, "label"),
        mapped(nodeValues, Node::label),
        false,
        value -> draft.setNodeLabel(nodeId, value));
    mergeTypedValue(
        pending,
        location(ConflictKind.DIVERGENT_VALUE, ElementKind.NODE, nodeId, "inputPorts"),
        mapped(nodeValues, Node::inputPorts),
        WorkflowSemanticValueFormatter::ports,
        value -> draft.setNodeInputPorts(nodeId, value));
    mergeTypedValue(
        pending,
        location(ConflictKind.DIVERGENT_VALUE, ElementKind.NODE, nodeId, "outputPorts"),
        mapped(nodeValues, Node::outputPorts),
        WorkflowSemanticValueFormatter::ports,
        value -> draft.setNodeOutputPorts(nodeId, value));
    mergeMetadata(
        pending,
        ElementKind.NODE,
        nodeId,
        nodeValues.base().metadata(),
        nodeValues.local().metadata(),
        nodeValues.remote().metadata(),
        (key, value) -> draft.setNodeMetadata(nodeId, key, value));
  }

  private static void mergeEdges(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      Map<String, Edge> baseEdges,
      Map<String, Edge> localEdges,
      Map<String, Edge> remoteEdges,
      Set<String> blockedEdgeIds) {
    for (String edgeId : union(baseEdges, localEdges, remoteEdges)) {
      if (blockedEdgeIds.contains(edgeId)) {
        continue;
      }
      MergeValues<Edge> edgeValues =
          values(baseEdges.get(edgeId), localEdges.get(edgeId), remoteEdges.get(edgeId));
      if (edgeValues.base() == null) {
        mergeAddedEdge(draft, pending, edgeId, edgeValues);
      } else if (edgeValues.local() == null || edgeValues.remote() == null) {
        mergeDeletedEdge(draft, pending, edgeId, edgeValues);
      } else {
        draft.putEdge(edgeValues.base());
        mergeEdgeFields(draft, pending, edgeValues);
      }
    }
  }

  private static void mergeAddedEdge(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String edgeId,
      MergeValues<Edge> edgeValues) {
    if (edgeValues.local() == null) {
      draft.putEdge(edgeValues.remote());
    } else if (edgeValues.remote() == null || edgeValues.local().equals(edgeValues.remote())) {
      draft.putEdge(edgeValues.local());
    } else {
      addEdgeObjectConflict(
          draft,
          pending,
          location(ConflictKind.STABLE_ID_COLLISION, ElementKind.EDGE, edgeId, "$object"),
          edgeValues);
    }
  }

  private static void mergeDeletedEdge(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String edgeId,
      MergeValues<Edge> edgeValues) {
    if (edgeValues.local() == null && edgeValues.remote() == null) {
      draft.removeEdge(edgeId);
    } else if (edgeValues.local() == null && edgeValues.base().equals(edgeValues.remote())) {
      draft.removeEdge(edgeId);
    } else if (edgeValues.remote() == null && edgeValues.base().equals(edgeValues.local())) {
      draft.removeEdge(edgeId);
    } else {
      addEdgeObjectConflict(
          draft,
          pending,
          location(ConflictKind.DELETE_MODIFY, ElementKind.EDGE, edgeId, "$object"),
          edgeValues);
    }
  }

  private static void addEdgeObjectConflict(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      MergeLocation location,
      MergeValues<Edge> edgeValues) {
    if (edgeValues.base() == null) {
      draft.removeEdge(location.elementId());
    } else {
      draft.putEdge(edgeValues.base());
    }
    Conflict conflict =
        conflict(
            location, formatted(edgeValues, WorkflowSemanticValueFormatter::edge), OBJECT_CHOICES);
    pending.add(
        new PendingConflict(
            conflict,
            resolution ->
                applyEdgeObjectResolution(draft, location, edgeValues, resolution.choice())));
  }

  private static void applyEdgeObjectResolution(
      WorkflowMergeDraft draft,
      MergeLocation location,
      MergeValues<Edge> edgeValues,
      ResolutionChoice choice) {
    Edge selected = selectObject(edgeValues, location, choice);
    if (selected == null) {
      draft.removeEdge(location.elementId());
    } else {
      draft.putEdge(selected);
    }
  }

  private static void mergeEdgeFields(
      WorkflowMergeDraft draft, List<PendingConflict> pending, MergeValues<Edge> edgeValues) {
    String edgeId = edgeValues.base().id();
    mergeTypedValue(
        pending,
        location(ConflictKind.DIVERGENT_EDGE_ENDPOINTS, ElementKind.EDGE, edgeId, "endpoints"),
        mapped(edgeValues, WorkflowThreeWayMerger::endpointsOnly),
        WorkflowSemanticValueFormatter::endpoints,
        value -> draft.setEdgeEndpoints(edgeId, value));
    mergeMetadata(
        pending,
        ElementKind.EDGE,
        edgeId,
        edgeValues.base().metadata(),
        edgeValues.local().metadata(),
        edgeValues.remote().metadata(),
        (key, value) -> draft.setEdgeMetadata(edgeId, key, value));
  }

  private static Edge endpointsOnly(Edge edge) {
    return new Edge(
        edge.id(),
        edge.sourceNodeId(),
        edge.sourcePortId(),
        edge.targetNodeId(),
        edge.targetPortId());
  }

  private static void mergeMetadata(
      List<PendingConflict> pending,
      ElementKind elementKind,
      String elementId,
      Metadata base,
      Metadata local,
      Metadata remote,
      BiConsumer<String, String> setter) {
    for (String key : union(base.entries(), local.entries(), remote.entries())) {
      mergeStringValue(
          pending,
          location(ConflictKind.DIVERGENT_VALUE, elementKind, elementId, "metadata." + key),
          values(base.entries().get(key), local.entries().get(key), remote.entries().get(key)),
          true,
          value -> setter.accept(key, value));
    }
  }

  private static void mergeStringValue(
      List<PendingConflict> pending,
      MergeLocation location,
      MergeValues<String> mergeValues,
      boolean optional,
      Consumer<String> setter) {
    String automatic = mergeValues.automatic();
    if (automatic != null || mergeValues.automaticallyResolved()) {
      setter.accept(automatic);
      return;
    }
    Set<ResolutionChoice> choices = optional ? OPTIONAL_STRING_CHOICES : CUSTOM_STRING_CHOICES;
    Conflict conflict = conflict(location, mergeValues, choices);
    pending.add(
        new PendingConflict(
            conflict,
            resolution ->
                setter.accept(selectString(mergeValues, optional, location, resolution))));
  }

  private static <T> void mergeTypedValue(
      List<PendingConflict> pending,
      MergeLocation location,
      MergeValues<T> mergeValues,
      Function<T, String> formatter,
      Consumer<T> setter) {
    T automatic = mergeValues.automatic();
    if (automatic != null || mergeValues.automaticallyResolved()) {
      setter.accept(automatic);
      return;
    }
    Conflict conflict = conflict(location, formatted(mergeValues, formatter), TYPED_VALUE_CHOICES);
    pending.add(
        new PendingConflict(
            conflict, resolution -> setter.accept(selectTyped(mergeValues, location, resolution))));
  }

  private static String selectString(
      MergeValues<String> mergeValues,
      boolean optional,
      MergeLocation location,
      Resolution resolution) {
    return switch (resolution.choice()) {
      case BASE -> mergeValues.base();
      case LOCAL -> mergeValues.local();
      case REMOTE -> mergeValues.remote();
      case DELETE -> {
        if (!optional) {
          throw unsupportedChoice(resolution.choice(), location.fieldPath());
        }
        yield null;
      }
      case CUSTOM -> resolution.customValue();
    };
  }

  private static <T> T selectTyped(
      MergeValues<T> mergeValues, MergeLocation location, Resolution resolution) {
    return switch (resolution.choice()) {
      case BASE -> mergeValues.base();
      case LOCAL -> mergeValues.local();
      case REMOTE -> mergeValues.remote();
      case DELETE, CUSTOM -> throw unsupportedChoice(resolution.choice(), location.fieldPath());
    };
  }

  private static <T> T selectObject(
      MergeValues<T> mergeValues, MergeLocation location, ResolutionChoice choice) {
    return switch (choice) {
      case BASE -> mergeValues.base();
      case LOCAL -> mergeValues.local();
      case REMOTE -> mergeValues.remote();
      case DELETE -> null;
      case CUSTOM -> throw unsupportedChoice(choice, location.fieldPath());
    };
  }

  private static Conflict conflict(
      MergeLocation location, MergeValues<String> evidence, Set<ResolutionChoice> allowedChoices) {
    return new Conflict(
        conflictId(location),
        location.kind(),
        location.elementKind(),
        location.elementId(),
        location.fieldPath(),
        evidence.base(),
        evidence.local(),
        evidence.remote(),
        allowedChoices);
  }

  private static String conflictId(MergeLocation location) {
    return location.kind().name()
        + ":"
        + location.elementKind().name()
        + ":"
        + segment(location.elementId())
        + ":"
        + segment(location.fieldPath());
  }

  private static String segment(String value) {
    return value.length() + ":" + value;
  }

  private static Map<String, Resolution> indexResolutions(List<Resolution> resolutions) {
    Map<String, Resolution> indexed = new TreeMap<>();
    for (Resolution resolution : resolutions) {
      Resolution required = Objects.requireNonNull(resolution, "resolution");
      if (indexed.putIfAbsent(required.conflictId(), required) != null) {
        throw new IllegalArgumentException(
            "Duplicate workflow merge resolution for " + required.conflictId());
      }
    }
    return indexed;
  }

  private static Map<String, Node> indexNodes(List<Node> nodes) {
    Map<String, Node> indexed = new TreeMap<>();
    for (Node node : nodes) {
      if (indexed.putIfAbsent(node.id(), node) != null) {
        throw new IllegalArgumentException("Duplicate node id: " + node.id());
      }
    }
    return indexed;
  }

  private static Map<String, Edge> indexEdges(List<Edge> edges) {
    Map<String, Edge> indexed = new TreeMap<>();
    for (Edge edge : edges) {
      if (indexed.putIfAbsent(edge.id(), edge) != null) {
        throw new IllegalArgumentException("Duplicate edge id: " + edge.id());
      }
    }
    return indexed;
  }

  private static boolean connectionsChanged(
      List<Edge> baseEdges, List<Edge> sideEdges, String nodeId) {
    return !WorkflowMergeDraft.connectedEdges(baseEdges, nodeId)
        .equals(WorkflowMergeDraft.connectedEdges(sideEdges, nodeId));
  }

  private static void addEdgeIds(Set<String> target, Collection<Edge> edges) {
    for (Edge edge : edges) {
      target.add(edge.id());
    }
  }

  private static <T> List<String> union(
      Map<String, T> base, Map<String, T> local, Map<String, T> remote) {
    TreeSet<String> keys = new TreeSet<>(base.keySet());
    keys.addAll(local.keySet());
    keys.addAll(remote.keySet());
    return List.copyOf(keys);
  }

  private static <T, R> MergeValues<R> mapped(MergeValues<T> values, Function<T, R> mapper) {
    return new MergeValues<>(
        mapper.apply(values.base()), mapper.apply(values.local()), mapper.apply(values.remote()));
  }

  private static <T> MergeValues<String> formatted(
      MergeValues<T> values, Function<T, String> formatter) {
    return new MergeValues<>(
        format(formatter, values.base()),
        format(formatter, values.local()),
        format(formatter, values.remote()));
  }

  private static <T> String format(Function<T, String> formatter, T value) {
    return value == null ? null : formatter.apply(value);
  }

  private static MergeLocation location(
      ConflictKind kind, ElementKind elementKind, String elementId, String fieldPath) {
    return new MergeLocation(kind, elementKind, elementId, fieldPath);
  }

  private static <T> MergeValues<T> values(T base, T local, T remote) {
    return new MergeValues<>(base, local, remote);
  }

  private static IllegalArgumentException unsupportedChoice(
      ResolutionChoice choice, String fieldPath) {
    return new IllegalArgumentException(
        "Resolution choice " + choice + " is not supported for " + fieldPath);
  }

  private record MergeLocation(
      ConflictKind kind, ElementKind elementKind, String elementId, String fieldPath) {
    MergeLocation {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(elementKind, "elementKind");
      Objects.requireNonNull(elementId, "elementId");
      Objects.requireNonNull(fieldPath, "fieldPath");
    }
  }

  private record MergeValues<T>(T base, T local, T remote) {
    boolean automaticallyResolved() {
      return Objects.equals(local, remote)
          || Objects.equals(local, base)
          || Objects.equals(remote, base);
    }

    T automatic() {
      if (Objects.equals(local, remote)) {
        return local;
      }
      if (Objects.equals(local, base)) {
        return remote;
      }
      if (Objects.equals(remote, base)) {
        return local;
      }
      return null;
    }
  }

  private record PendingConflict(Conflict conflict, Consumer<Resolution> applier) {
    PendingConflict {
      Objects.requireNonNull(conflict, "conflict");
      Objects.requireNonNull(applier, "applier");
    }

    void apply(Resolution resolution) {
      if (!conflict.allowedChoices().contains(resolution.choice())) {
        throw unsupportedChoice(resolution.choice(), conflict.fieldPath());
      }
      applier.accept(resolution);
    }
  }

  private record Computation(WorkflowMergeDraft draft, List<PendingConflict> pending) {
    Computation {
      Objects.requireNonNull(draft, "draft");
      pending = List.copyOf(Objects.requireNonNull(pending, "pending"));
      Set<String> identities = new HashSet<>();
      for (PendingConflict conflict : pending) {
        if (!identities.add(conflict.conflict().conflictId())) {
          throw new IllegalStateException(
              "Duplicate calculated merge conflict " + conflict.conflict().conflictId());
        }
      }
    }
  }

  private record SpecialNodes(Map<String, SpecialNode> byId, Set<String> blockedEdgeIds) {
    SpecialNodes {
      byId = Map.copyOf(Objects.requireNonNull(byId, "byId"));
      blockedEdgeIds = Set.copyOf(Objects.requireNonNull(blockedEdgeIds, "blockedEdgeIds"));
    }

    Set<String> nodeIds() {
      return byId.keySet();
    }
  }

  private record SpecialNode(
      MergeLocation location,
      Node base,
      Node local,
      Node remote,
      List<Edge> baseEdges,
      List<Edge> localEdges,
      List<Edge> remoteEdges,
      Set<String> relatedEdgeIds) {
    SpecialNode {
      Objects.requireNonNull(location, "location");
      baseEdges = List.copyOf(Objects.requireNonNull(baseEdges, "baseEdges"));
      localEdges = List.copyOf(Objects.requireNonNull(localEdges, "localEdges"));
      remoteEdges = List.copyOf(Objects.requireNonNull(remoteEdges, "remoteEdges"));
      relatedEdgeIds = Set.copyOf(Objects.requireNonNull(relatedEdgeIds, "relatedEdgeIds"));
    }
  }
}
