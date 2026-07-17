# Hibernate-backed workflow persistence

The normal workbench starts in `memory` mode. Checkpoint, history and load endpoints require an
explicit persistence mode.

## Production/development Hibernate mode

Audio Analyzer consumes:

```text
io.github.carstenartur:jgit-storage-hibernate-core:0.1.4
Hibernate ORM 7.4.5.Final
```

The application creates one Spring-managed `DataSource` and one application-owned Hibernate
`SessionFactory`. The shared JGit storage entities and future Audio Analyzer collaboration/outbox
entities use that same persistence context.

Example local startup:

```bash
java -jar audio-app/target/audio-app-0.0.4-SNAPSHOT-workbench.jar \
  --workbench.persistence.mode=hibernate \
  --workbench.persistence.repository-name=audio-analyzer-workflows \
  --workbench.persistence.schema-action=update \
  --spring.datasource.url='jdbc:h2:file:./data/audio-analyzer;AUTO_SERVER=TRUE' \
  --spring.datasource.driver-class-name=org.h2.Driver \
  --spring.datasource.username=sa \
  --spring.datasource.password=
```

`update` is intended only for disposable development data. Production deployments should provision
schema changes externally and use:

```properties
workbench.persistence.schema-action=validate
```

Starting `hibernate` mode without an explicit `spring.datasource.url` or
`spring.datasource.jndi-name` fails immediately with an actionable configuration error.

## Application-specific entity registration

Audio Analyzer modules register only their own ORM mappings through a
`WorkflowPersistenceEntityContributor` bean:

```java
@Bean
WorkflowPersistenceEntityContributor collaborationPersistenceEntities() {
  return () -> List.of(
      WorkflowSessionEntity.class,
      WorkflowOperationEntity.class,
      WorkflowOutboxEntity.class);
}
```

The resulting classes are added to the same application-managed `SessionFactory` as the generic
JGit storage entities. This is the extension point used by #245; it does not copy or replace any
mapping from `jgit-storage-hibernate-core`.

## GitHub Packages access

The artifact is currently published through GitHub Packages. Maven uses repository id `github`.
Local users need a settings entry with a token that has `read:packages`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

The repository CI configures this server from its `GITHUB_TOKEN` and requests `packages: read`.

## Explicit filesystem fallback

The old local bare-repository implementation remains available under an unambiguous mode and class
name:

```bash
java -jar audio-app/target/audio-app-0.0.4-SNAPSHOT-workbench.jar \
  --workbench.persistence.mode=filesystem \
  --workbench.persistence.filesystem.path=./data/workbench.git
```

This mode is for tests and local demonstrations. It is never selected by the production persistent
profile.

## Ownership and transaction boundary

`jgit-storage-hibernate-core` owns only generic JGit storage. Audio Analyzer owns the workflow tree
layout and, in #245, its collaboration-session, operation, revision and outbox entities.

A live semantic operation and outbox record will share one Hibernate transaction. A Git checkpoint
is a separate application command based on an expected semantic revision. Cross-component atomicity
must not be claimed unless the shared storage library exposes and verifies a matching generic
transaction participation contract.
