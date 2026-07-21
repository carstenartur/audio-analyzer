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
import org.hammer.audio.workflow.Edge;
import org.hammer.audio.workflow.Metadata;
import org.hammer.audio.workflow.Node;
import org.hammer.audio.workflow.Port;
import org.hammer.audio.workflow.Workflow;
import org.hammer.audio.workflow.WorkflowValidator;
import org.hammer.audio.workflow.merge.WorkflowDiffModels.ElementKind;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.Conflict;
import org.hammer.audio.workflow.merge.WorkflowMergeModels.ConflictKind;
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
          EnumSet.of(
              ResolutionChoice.BASE, ResolutionChoice.LOCAL, ResolutionChoice.REMOTE));
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
    Map<String, Resolution> byConflictId = indexResolutions(resolutions);
    Set<String> knownConflictIds =
        computation.pending().stream()
            .map(pending -> pending.conflict().conflictId())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    for (String conflictId : byConflictId.keySet()) {
      if (!knownConflictIds.contains(conflictId)) {
        throw new IllegalArgumentException("Unknown workflow merge conflict: " + conflictId);
      }
    }

    List<Conflict> unresolved = new ArrayList<>();
    for (PendingConflict pending : computation.pending()) {
      Resolution resolution = byConflictId.get(pending.conflict().conflictId());
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
        ConflictKind.DIVERGENT_VALUE,
        ElementKind.WORKFLOW,
        base.id(),
        "name",
        base.name(),
        local.name(),
        remote.name(),
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
            baseNodes,
            localNodes,
            remoteNodes,
            base.edges(),
            local.edges(),
            remote.edges());
    installSpecialNodeConflicts(draft, pending, specialNodes);
    mergeNodes(draft, pending, baseNodes, localNodes, remoteNodes, specialNodes.nodeIds());
    mergeEdges(
        draft,
        pending,
        baseEdges,
        localEdges,
        remoteEdges,
        specialNodes.blockedEdgeIds());

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
      ConflictKind conflictKind = null;
      String fieldPath = null;
      if (base == null && local != null && remote != null && !local.equals(remote)) {
        conflictKind = ConflictKind.STABLE_ID_COLLISION;
        fieldPath = "$object";
      } else if (base != null
          && local == null
          && remote != null
          && connectionsChanged(baseEdges, remoteEdges, nodeId)) {
        conflictKind = ConflictKind.DELETE_CONNECT;
        fieldPath = "connections";
      } else if (base != null
          && remote == null
          && local != null
          && connectionsChanged(baseEdges, localEdges, nodeId)) {
        conflictKind = ConflictKind.DELETE_CONNECT;
        fieldPath = "connections";
      }
      if (conflictKind == null) {
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
              conflictKind,
              fieldPath,
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

  private static void installSpecialNodeConflicts(
      WorkflowMergeDraft draft, List<PendingConflict> pending, SpecialNodes specialNodes) {
    for (Map.Entry<String, SpecialNode> entry : specialNodes.byId().entrySet()) {
      String nodeId = entry.getKey();
      SpecialNode special = entry.getValue();
      draft.replaceNodeNeighborhood(
          nodeId, special.base(), special.baseEdges(), special.relatedEdgeIds());
      Conflict conflict =
          conflict(
              special.kind(),
              ElementKind.NODE,
              nodeId,
              special.fieldPath(),
              WorkflowSemanticValues.neighborhood(special.base(), special.baseEdges()),
              WorkflowSemanticValues.neighborhood(special.local(), special.localEdges()),
              WorkflowSemanticValues.neighborhood(special.remote(), special.remoteEdges()),
              OBJECT_CHOICES);
      pending.add(
          new PendingConflict(
              conflict,
              resolution ->
                  applyNeighborhoodResolution(draft, nodeId, special, resolution.choice())));
    }
  }

  private static void applyNeighborhoodResolution(
      WorkflowMergeDraft draft,
      String nodeId,
      SpecialNode special,
      ResolutionChoice choice) {
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
      case CUSTOM -> throw unsupportedChoice(choice, special.fieldPath());
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
      Node base = baseNodes.get(nodeId);
      Node local = localNodes.get(nodeId);
      Node remote = remoteNodes.get(nodeId);
      if (base == null) {
        mergeAddedNode(draft, pending, nodeId, local, remote);
      } else if (local == null || remote == null) {
        mergeDeletedNode(draft, pending, nodeId, base, local, remote);
      } else {
        draft.putNode(base);
        mergeNodeFields(draft, pending, base, local, remote);
      }
    }
  }

  private static void mergeAddedNode(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String nodeId,
      Node local,
      Node remote) {
    if (local == null) {
      draft.putNode(remote);
    } else if (remote == null || local.equals(remote)) {
      draft.putNode(local);
    } else {
      addNodeObjectConflict(
          draft,
          pending,
          ConflictKind.STABLE_ID_COLLISION,
          nodeId,
          null,
          local,
          remote);
    }
  }

  private static void mergeDeletedNode(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String nodeId,
      Node base,
      Node local,
      Node remote) {
    if (local == null && remote == null) {
      draft.removeNode(nodeId);
    } else if (local == null && base.equals(remote)) {
      draft.removeNode(nodeId);
    } else if (remote == null && base.equals(local)) {
      draft.removeNode(nodeId);
    } else {
      addNodeObjectConflict(
          draft, pending, ConflictKind.DELETE_MODIFY, nodeId, base, local, remote);
    }
  }

  private static void addNodeObjectConflict(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      ConflictKind kind,
      String nodeId,
      Node base,
      Node local,
      Node remote) {
    if (base == null) {
      draft.removeNode(nodeId);
    } else {
      draft.putNode(base);
    }
    Conflict conflict =
        conflict(
            kind,
            ElementKind.NODE,
            nodeId,
            "$object",
            base == null ? null : WorkflowSemanticValues.node(base),
            local == null ? null : WorkflowSemanticValues.node(local),
            remote == null ? null : WorkflowSemanticValues.node(remote),
            OBJECT_CHOICES);
    pending.add(
        new PendingConflict(
            conflict,
            resolution ->
                applyNodeObjectResolution(
                    draft, nodeId, base, local, remote, resolution.choice())));
  }

  private static void applyNodeObjectResolution(
      WorkflowMergeDraft draft,
      String nodeId,
      Node base,
      Node local,
      Node remote,
      ResolutionChoice choice) {
    Node selected =
        switch (choice) {
          case BASE -> base;
          case LOCAL -> local;
          case REMOTE -> remote;
          case DELETE -> null;
          case CUSTOM -> throw unsupportedChoice(choice, "$object");
        };
    if (selected == null) {
      draft.removeNode(nodeId);
    } else {
      draft.putNode(selected);
    }
  }

  private static void mergeNodeFields(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      Node base,
      Node local,
      Node remote) {
    String nodeId = base.id();
    mergeTypedValue(
        pending,
        ConflictKind.DIVERGENT_VALUE,
        ElementKind.NODE,
        nodeId,
        "type",
        base.type(),
        local.type(),
        remote.type(),
        Function.identity(),
        value -> draft.setNodeType(nodeId, value));
    mergeStringValue(
        pending,
        ConflictKind.DIVERGENT_VALUE,
        ElementKind.NODE,
        nodeId,
        "label",
        base.label(),
        local.label(),
        remote.label(),
        false,
        value -> draft.setNodeLabel(nodeId, value));
    mergeTypedValue(
        pending,
        ConflictKind.DIVERGENT_VALUE,
        ElementKind.NODE,
        nodeId,
        "inputPorts",
        base.inputPorts(),
        local.inputPorts(),
        remote.inputPorts(),
        WorkflowSemanticValues::ports,
        value -> draft.setNodeInputPorts(nodeId, value));
    mergeTypedValue(
        pending,
        ConflictKind.DIVERGENT_VALUE,
        ElementKind.NODE,
        nodeId,
        "outputPorts",
        base.outputPorts(),
        local.outputPorts(),
        remote.outputPorts(),
        WorkflowSemanticValues::ports,
        value -> draft.setNodeOutputPorts(nodeId, value));
    mergeMetadata(
        pending,
        ElementKind.NODE,
        nodeId,
        base.metadata(),
        local.metadata(),
        remote.metadata(),
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
      Edge base = baseEdges.get(edgeId);
      Edge local = localEdges.get(edgeId);
      Edge remote = remoteEdges.get(edgeId);
      if (base == null) {
        mergeAddedEdge(draft, pending, edgeId, local, remote);
      } else if (local == null || remote == null) {
        mergeDeletedEdge(draft, pending, edgeId, base, local, remote);
      } else {
        draft.putEdge(base);
        mergeEdgeFields(draft, pending, base, local, remote);
      }
    }
  }

  private static void mergeAddedEdge(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String edgeId,
      Edge local,
      Edge remote) {
    if (local == null) {
      draft.putEdge(remote);
    } else if (remote == null || local.equals(remote)) {
      draft.putEdge(local);
    } else {
      addEdgeObjectConflict(
          draft,
          pending,
          ConflictKind.STABLE_ID_COLLISION,
          edgeId,
          null,
          local,
          remote);
    }
  }

  private static void mergeDeletedEdge(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      String edgeId,
      Edge base,
      Edge local,
      Edge remote) {
    if (local == null && remote == null) {
      draft.removeEdge(edgeId);
    } else if (local == null && base.equals(remote)) {
      draft.removeEdge(edgeId);
    } else if (remote == null && base.equals(local)) {
      draft.removeEdge(edgeId);
    } else {
      addEdgeObjectConflict(
          draft, pending, ConflictKind.DELETE_MODIFY, edgeId, base, local, remote);
    }
  }

  private static void addEdgeObjectConflict(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      ConflictKind kind,
      String edgeId,
      Edge base,
      Edge local,
      Edge remote) {
    if (base == null) {
      draft.removeEdge(edgeId);
    } else {
      draft.putEdge(base);
    }
    Conflict conflict =
        conflict(
            kind,
            ElementKind.EDGE,
            edgeId,
            "$object",
            base == null ? null : WorkflowSemanticValues.edge(base),
            local == null ? null : WorkflowSemanticValues.edge(local),
            remote == null ? null : WorkflowSemanticValues.edge(remote),
            OBJECT_CHOICES);
    pending.add(
        new PendingConflict(
            conflict,
            resolution ->
                applyEdgeObjectResolution(
                    draft, edgeId, base, local, remote, resolution.choice())));
  }

  private static void applyEdgeObjectResolution(
      WorkflowMergeDraft draft,
      String edgeId,
      Edge base,
      Edge local,
      Edge remote,
      ResolutionChoice choice) {
    Edge selected =
        switch (choice) {
          case BASE -> base;
          case LOCAL -> local;
          case REMOTE -> remote;
          case DELETE -> null;
          case CUSTOM -> throw unsupportedChoice(choice, "$object");
        };
    if (selected == null) {
      draft.removeEdge(edgeId);
    } else {
      draft.putEdge(selected);
    }
  }

  private static void mergeEdgeFields(
      WorkflowMergeDraft draft,
      List<PendingConflict> pending,
      Edge base,
      Edge local,
      Edge remote) {
    String edgeId = base.id();
    mergeTypedValue(
        pending,
        ConflictKind.DIVERGENT_EDGE_ENDPOINTS,
        ElementKind.EDGE,
        edgeId,
        "endpoints",
        endpointsOnly(base),
        endpointsOnly(local),
        endpointsOnly(remote),
        WorkflowSemanticValues::endpoints,
        value -> draft.setEdgeEndpoints(edgeId, value));
    mergeMetadata(
        pending,
        ElementKind.EDGE,
        edgeId,
        base.metadata(),
        local.metadata(),
        remote.metadata(),
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
          ConflictKind.DIVERGENT_VALUE,
          elementKind,
          elementId,
          "metadata." + key,
          base.entries().get(key),
          local.entries().get(key),
          remote.entries().get(key),
          true,
          value -> setter.accept(key, value));
    }
  }

  private static void mergeStringValue(
      List<PendingConflict> pending,
      ConflictKind kind,
      ElementKind elementKind,
      String elementId,
      String fieldPath,
      String base,
      String local,
      String remote,
      boolean optional,
      Consumer<String> setter) {
    String automatic = automatic(base, local, remote);
    if (automatic != null || isAutomaticallyResolved(base, local, remote)) {
      setter.accept(automatic);
      return;
    }
    Set<ResolutionChoice> choices = optional ? OPTIONAL_STRING_CHOICES : CUSTOM_STRING_CHOICES;
    Conflict conflict =
        conflict(kind, elementKind, elementId, fieldPath, base, local, remote, choices);
    pending.add(
        new PendingConflict(
            conflict,
            resolution ->
                setter.accept(
                    selectString(base, local, remote, optional, fieldPath, resolution))));
  }

  private static <T> void mergeTypedValue(
      List<PendingConflict> pending,
      ConflictKind kind,
      ElementKind elementKind,
      String elementId,
      String fieldPath,
      T base,
      T local,
      T remote,
      Function<T, String> formatter,
      Consumer<T> setter) {
    T automatic = automatic(base, local, remote);
    if (automatic != null || isAutomaticallyResolved(base, local, remote)) {
      setter.accept(automatic);
      return;
    }
    Conflict conflict =
        conflict(
            kind,
            elementKind,
            elementId,
            fieldPath,
            format(formatter, base),
            format(formatter, local),
            format(formatter, remote),
            TYPED_VALUE_CHOICES);
    pending.add(
        new PendingConflict(
            conflict,
            resolution -> setter.accept(selectTyped(base, local, remote, fieldPath, resolution))));
  }

  private static boolean isAutomaticallyResolved(Object base, Object local, Object remote) {
    return Objects.equals(local, remote)
        || Objects.equals(local, base)
        || Objects.equals(remote, base);
  }

  private static <T> T automatic(T base, T local, T remote) {
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

  private static String selectString(
      String base,
      String local,
      String remote,
      boolean optional,
      String fieldPath,
      Resolution resolution) {
    return switch (resolution.choice()) {
      case BASE -> base;
      case LOCAL -> local;
      case REMOTE -> remote;
      case DELETE -> {
        if (!optional) {
          throw unsupportedChoice(resolution.choice(), fieldPath);
        }
        yield null;
      }
      case CUSTOM -> resolution.customValue();
    };
  }

  private static <T> T selectTyped(
      T base, T local, T remote, String fieldPath, Resolution resolution) {
    return switch (resolution.choice()) {
      case BASE -> base;
      case LOCAL -> local;
      case REMOTE -> remote;
      case DELETE, CUSTOM -> throw unsupportedChoice(resolution.choice(), fieldPath);
    };
  }

  private static Conflict conflict(
      ConflictKind kind,
      ElementKind elementKind,
      String elementId,
      String fieldPath,
      String baseValue,
      String localValue,
      String remoteValue,
      Set<ResolutionChoice> allowedChoices) {
    return new Conflict(
        conflictId(kind, elementKind, elementId, fieldPath),
        kind,
        elementKind,
        elementId,
        fieldPath,
        baseValue,
        localValue,
        remoteValue,
        allowedChoices);
  }

  private static String conflictId(
      ConflictKind kind, ElementKind elementKind, String elementId, String fieldPath) {
    return kind.name()
        + ":"
        + elementKind.name()
        + ":"
        + segment(elementId)
        + ":"
        + segment(fieldPath);
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

  private static <T> String format(Function<T, String> formatter, T value) {
    return value == null ? null : formatter.apply(value);
  }

  private static IllegalArgumentException unsupportedChoice(
      ResolutionChoice choice, String fieldPath) {
    return new IllegalArgumentException(
        "Resolution choice " + choice + " is not supported for " + fieldPath);
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

  private record SpecialNodes(
      Map<String, SpecialNode> byId, Set<String> blockedEdgeIds) {
    SpecialNodes {
      byId = Map.copyOf(Objects.requireNonNull(byId, "byId"));
      blockedEdgeIds = Set.copyOf(Objects.requireNonNull(blockedEdgeIds, "blockedEdgeIds"));
    }

    Set<String> nodeIds() {
      return byId.keySet();
    }
  }

  private record SpecialNode(
      ConflictKind kind,
      String fieldPath,
      Node base,
      Node local,
      Node remote,
      List<Edge> baseEdges,
      List<Edge> localEdges,
      List<Edge> remoteEdges,
      Set<String> relatedEdgeIds) {
    SpecialNode {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(fieldPath, "fieldPath");
      baseEdges = List.copyOf(Objects.requireNonNull(baseEdges, "baseEdges"));
      localEdges = List.copyOf(Objects.requireNonNull(localEdges, "localEdges"));
      remoteEdges = List.copyOf(Objects.requireNonNull(remoteEdges, "remoteEdges"));
      relatedEdgeIds = Set.copyOf(Objects.requireNonNull(relatedEdgeIds, "relatedEdgeIds"));
    }
  }
}
