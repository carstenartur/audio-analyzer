package org.hammer.audio.infrastructure.workflow.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.util.List;
import org.hibernate.annotations.Nationalized;
import org.hibernate.search.engine.backend.analysis.AnalyzerNames;
import org.hibernate.search.engine.backend.types.Sortable;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.FullTextField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.GenericField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.Indexed;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.IndexingDependency;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.KeywordField;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.ObjectPath;
import org.hibernate.search.mapper.pojo.mapping.definition.annotation.PropertyValue;

/** Branch-aware, rebuildable semantic projection of one authoritative workflow commit. */
@Entity
@Indexed
@Table(
    name = "workflow_semantic_index",
    indexes = {
      @Index(
          name = "idx_workflow_semantic_repo_branch_position",
          columnList = "repository_name, branch_name, branch_position"),
      @Index(
          name = "idx_workflow_semantic_repo_workflow",
          columnList = "repository_name, workflow_id")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_workflow_semantic_repo_branch_commit",
          columnNames = {"repository_name", "branch_name", "object_id"})
    })
public class WorkflowSemanticIndexEntity {

  static final String NODE_IDS_FIELD = "nodeIds";
  static final String NODE_TYPES_FIELD = "nodeTypes";
  static final String NODE_LABELS_FIELD = "nodeLabels";
  static final String PROPERTY_KEYS_FIELD = "propertyKeys";
  static final String PROPERTY_VALUES_FIELD = "propertyValues";
  static final String PROPERTY_PAIRS_FIELD = "propertyPairs";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @KeywordField
  @Column(name = "repository_name", nullable = false, length = 255)
  private String repositoryName;

  @KeywordField
  @Nationalized
  @Column(name = "branch_name", nullable = false, length = 255)
  private String branchName;

  @KeywordField
  @Column(name = "object_id", nullable = false, length = 40)
  private String objectId;

  @GenericField(sortable = Sortable.YES)
  @Column(name = "branch_position", nullable = false)
  private int branchPosition;

  @KeywordField
  @Nationalized
  @Column(name = "workflow_id", nullable = false, length = 255)
  private String workflowId;

  @FullTextField(analyzer = AnalyzerNames.STANDARD)
  @Nationalized
  @Column(name = "workflow_name", nullable = false, length = 2048)
  private String workflowName;

  @Column(name = "node_ids", nullable = false, length = 65535)
  private String encodedNodeIds;

  @Column(name = "node_types", nullable = false, length = 65535)
  private String encodedNodeTypes;

  @Column(name = "node_labels", nullable = false, length = 65535)
  private String encodedNodeLabels;

  @FullTextField(analyzer = AnalyzerNames.STANDARD)
  @Nationalized
  @Column(name = "node_label_text", nullable = false, length = 65535)
  private String nodeLabelText;

  @Column(name = "property_keys", nullable = false, length = 65535)
  private String encodedPropertyKeys;

  @Column(name = "property_values", nullable = false, length = 65535)
  private String encodedPropertyValues;

  @Column(name = "property_pairs", nullable = false, length = 65535)
  private String encodedPropertyPairs;

  protected WorkflowSemanticIndexEntity() {
    // Required by Jakarta Persistence.
  }

  static WorkflowSemanticIndexEntity create(
      String repositoryName,
      String branchName,
      String objectId,
      int branchPosition,
      WorkflowSemanticProjectionValues values) {
    WorkflowSemanticIndexEntity entity = new WorkflowSemanticIndexEntity();
    entity.repositoryName = repositoryName;
    entity.branchName = branchName;
    entity.objectId = objectId;
    entity.branchPosition = branchPosition;
    entity.apply(values);
    return entity;
  }

  void apply(WorkflowSemanticProjectionValues values) {
    workflowId = values.workflowId();
    workflowName = values.workflowName();
    encodedNodeIds = WorkflowSemanticProjectionValues.encodeValues(values.nodeIds());
    encodedNodeTypes = WorkflowSemanticProjectionValues.encodeValues(values.nodeTypes());
    encodedNodeLabels = WorkflowSemanticProjectionValues.encodeValues(values.nodeLabels());
    nodeLabelText = String.join("\n", values.nodeLabels());
    encodedPropertyKeys = WorkflowSemanticProjectionValues.encodeValues(values.propertyKeys());
    encodedPropertyValues = WorkflowSemanticProjectionValues.encodeValues(values.propertyValues());
    encodedPropertyPairs = String.join("\n", values.propertyPairs());
  }

  void setBranchPosition(int branchPosition) {
    this.branchPosition = branchPosition;
  }

  String repositoryName() {
    return repositoryName;
  }

  String branchName() {
    return branchName;
  }

  String objectId() {
    return objectId;
  }

  int branchPosition() {
    return branchPosition;
  }

  String workflowId() {
    return workflowId;
  }

  String workflowName() {
    return workflowName;
  }

  @Transient
  @KeywordField(name = NODE_IDS_FIELD)
  @IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "encodedNodeIds")))
  public List<String> getNodeIds() {
    return WorkflowSemanticProjectionValues.decodeValues(encodedNodeIds);
  }

  @Transient
  @KeywordField(name = NODE_TYPES_FIELD)
  @IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "encodedNodeTypes")))
  public List<String> getNodeTypes() {
    return WorkflowSemanticProjectionValues.decodeValues(encodedNodeTypes);
  }

  @Transient
  @KeywordField(name = NODE_LABELS_FIELD)
  @IndexingDependency(derivedFrom = @ObjectPath(@PropertyValue(propertyName = "encodedNodeLabels")))
  public List<String> getNodeLabels() {
    return WorkflowSemanticProjectionValues.decodeValues(encodedNodeLabels);
  }

  @Transient
  @KeywordField(name = PROPERTY_KEYS_FIELD)
  @IndexingDependency(
      derivedFrom = @ObjectPath(@PropertyValue(propertyName = "encodedPropertyKeys")))
  public List<String> getPropertyKeys() {
    return WorkflowSemanticProjectionValues.decodeValues(encodedPropertyKeys);
  }

  @Transient
  @KeywordField(name = PROPERTY_VALUES_FIELD)
  @IndexingDependency(
      derivedFrom = @ObjectPath(@PropertyValue(propertyName = "encodedPropertyValues")))
  public List<String> getPropertyValues() {
    return WorkflowSemanticProjectionValues.decodeValues(encodedPropertyValues);
  }

  @Transient
  @KeywordField(name = PROPERTY_PAIRS_FIELD)
  @IndexingDependency(
      derivedFrom = @ObjectPath(@PropertyValue(propertyName = "encodedPropertyPairs")))
  public List<String> getPropertyPairs() {
    return encodedPropertyPairs == null || encodedPropertyPairs.isBlank()
        ? List.of()
        : encodedPropertyPairs.lines().filter(value -> !value.isBlank()).toList();
  }
}
