package org.hammer.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsReftableDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.internal.storage.reftable.ReftableConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

class RegularJGitStoragePrototypeTest {

  private static final String MAIN_REF = "refs/heads/main";
  private static final int DEFAULT_BLOCK_SIZE = 4096;
  private static final PersonIdent COMMITTER =
      new PersonIdent(
          "JGit Spike",
          "jgit-spike@example.invalid",
          Instant.parse("2026-07-06T00:00:00Z"),
          ZoneOffset.UTC);

  @Test
  void blobTreeCommitRefAndReflogRoundTripWorksAgainstRegularJGit() throws Exception {
    SharedDatabase database = new SharedDatabase();

    try (PrototypeRepository repository = database.open("roundtrip")) {
      StoredCommit initialCommit =
          writeCommit(repository, null, "workflow.dsl", "Input -> Gain(0.5) -> Output", "initial");
      RefUpdate.Result initialResult =
          updateRef(repository, MAIN_REF, null, initialCommit.commitId(), "workflow: initial");

      StoredCommit secondCommit =
          writeCommit(
              repository,
              initialCommit.commitId(),
              "workflow.dsl",
              "Input -> Gain(0.75) -> Output",
              "gain tweak");
      RefUpdate.Result secondResult =
          updateRef(
              repository,
              MAIN_REF,
              initialCommit.commitId(),
              secondCommit.commitId(),
              "workflow: gain tweak");

      assertEquals(RefUpdate.Result.NEW, initialResult);
      assertEquals(RefUpdate.Result.FAST_FORWARD, secondResult);
      assertEquals(secondCommit.commitId(), repository.resolve(MAIN_REF));
      assertEquals(
          "Input -> Gain(0.75) -> Output",
          readBlob(repository, secondCommit.treeId(), "workflow.dsl"));
      assertEquals(
          List.of(secondCommit.commitId(), initialCommit.commitId()),
          history(repository, MAIN_REF));

      assertTrue(reflogEntries(repository, MAIN_REF).isEmpty());
    }
  }

  @Test
  void concurrentWritersSeeAtomicRefConflicts() throws Exception {
    SharedDatabase database = new SharedDatabase();
    StoredCommit baseCommit;
    StoredCommit candidateA;
    StoredCommit candidateB;

    try (PrototypeRepository repository = database.open("concurrency")) {
      baseCommit =
          writeCommit(repository, null, "workflow.dsl", "Input -> Gain(1.0) -> Output", "base");
      assertEquals(
          RefUpdate.Result.NEW,
          updateRef(repository, MAIN_REF, null, baseCommit.commitId(), "workflow: base"));
      candidateA =
          writeCommit(
              repository,
              baseCommit.commitId(),
              "workflow.dsl",
              "Input -> Gain(0.8) -> Output",
              "A");
      candidateB =
          writeCommit(
              repository,
              baseCommit.commitId(),
              "workflow.dsl",
              "Input -> Gain(1.2) -> Output",
              "B");
    }

    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<UpdateOutcome> writerA =
          executor.submit(
              updateTask(
                  database,
                  "concurrency",
                  start,
                  baseCommit.commitId(),
                  candidateA.commitId(),
                  "A"));
      Future<UpdateOutcome> writerB =
          executor.submit(
              updateTask(
                  database,
                  "concurrency",
                  start,
                  baseCommit.commitId(),
                  candidateB.commitId(),
                  "B"));

      start.countDown();
      UpdateOutcome resultA = get(writerA);
      UpdateOutcome resultB = get(writerB);

      long successCount = List.of(resultA, resultB).stream().filter(UpdateOutcome::success).count();
      assertEquals(
          1,
          successCount,
          () -> "expected one successful ref update but saw " + List.of(resultA, resultB));
      assertEquals(
          1,
          2 - successCount,
          () -> "expected one conflicting ref update but saw " + List.of(resultA, resultB));

      try (PrototypeRepository repository = database.open("concurrency")) {
        ObjectId winner = repository.resolve(MAIN_REF);
        assertTrue(
            winner.equals(candidateA.commitId()) || winner.equals(candidateB.commitId()),
            () -> "unexpected winner: " + winner.name());
      }
    }
  }

  @Test
  void failingBeforeRefUpdateLeavesHeadStableAndDoesNotAppendReflog() throws Exception {
    SharedDatabase database = new SharedDatabase();

    try (PrototypeRepository repository = database.open("rollback")) {
      StoredCommit baseCommit =
          writeCommit(repository, null, "workflow.dsl", "Input -> Gain(1.0) -> Output", "base");
      assertEquals(
          RefUpdate.Result.NEW,
          updateRef(repository, MAIN_REF, null, baseCommit.commitId(), "workflow: base"));

      StoredCommit orphanCommit =
          writeCommit(
              repository,
              baseCommit.commitId(),
              "workflow.dsl",
              "Input -> Gain(0.25) -> Output",
              "failing candidate");

      RuntimeException failure =
          new RuntimeException("simulated failure after object write, before ref update");
      assertEquals(
          "simulated failure after object write, before ref update",
          assertThrowsAndReturnMessage(failure));

      assertEquals(baseCommit.commitId(), repository.resolve(MAIN_REF));
      assertEquals(List.of(baseCommit.commitId()), history(repository, MAIN_REF));
      assertEquals(
          "Input -> Gain(0.25) -> Output",
          readBlob(repository, orphanCommit.treeId(), "workflow.dsl"));

      assertTrue(reflogEntries(repository, MAIN_REF).isEmpty());
    }
  }

  @Test
  void closeReopenAndScanForRepoChangesPreserveDataWithoutCrossRepoLeakage() throws Exception {
    SharedDatabase database = new SharedDatabase();
    StoredCommit baseCommit;
    StoredCommit updatedCommit;

    try (PrototypeRepository writer = database.open("repo-a")) {
      baseCommit =
          writeCommit(writer, null, "workflow.dsl", "Input -> Gain(1.0) -> Output", "base");
      assertEquals(
          RefUpdate.Result.NEW,
          updateRef(writer, MAIN_REF, null, baseCommit.commitId(), "workflow: base"));
    }

    try (PrototypeRepository reopened = database.open("repo-a")) {
      assertEquals(baseCommit.commitId(), reopened.resolve(MAIN_REF));
      assertEquals(
          "Input -> Gain(1.0) -> Output", readBlob(reopened, baseCommit.treeId(), "workflow.dsl"));
    }

    try (PrototypeRepository staleReader = database.open("repo-a");
        PrototypeRepository writer = database.open("repo-a")) {
      assertEquals(baseCommit.commitId(), staleReader.resolve(MAIN_REF));
      updatedCommit =
          writeCommit(
              writer,
              baseCommit.commitId(),
              "workflow.dsl",
              "Input -> Gain(1.5) -> Output",
              "update");
      assertEquals(
          RefUpdate.Result.FAST_FORWARD,
          updateRef(
              writer,
              MAIN_REF,
              baseCommit.commitId(),
              updatedCommit.commitId(),
              "workflow: update"));

      assertEquals(baseCommit.commitId(), staleReader.resolve(MAIN_REF));
      staleReader.scanForRepoChanges();
      assertEquals(updatedCommit.commitId(), staleReader.resolve(MAIN_REF));
    }

    try (PrototypeRepository otherRepository = database.open("repo-b")) {
      StoredCommit otherCommit =
          writeCommit(
              otherRepository, null, "workflow.dsl", "Input -> Gain(9.0) -> Output", "other");
      assertEquals(
          RefUpdate.Result.NEW,
          updateRef(otherRepository, MAIN_REF, null, otherCommit.commitId(), "workflow: other"));
      assertEquals(
          "Input -> Gain(9.0) -> Output",
          readBlob(otherRepository, otherCommit.treeId(), "workflow.dsl"));
    }

    assertEquals(
        2,
        database.repositoryNames().size(),
        () -> "expected two logical repositories but found " + database.repositoryNames());
    assertEquals(database.allPackNames().size(), database.distinctPackNames().size());
    assertTrue(database.allPackNames().stream().anyMatch(name -> name.startsWith("repo-a-")));
    assertTrue(database.allPackNames().stream().anyMatch(name -> name.startsWith("repo-b-")));
  }

  private static Callable<UpdateOutcome> updateTask(
      SharedDatabase database,
      String repositoryName,
      CountDownLatch start,
      ObjectId expectedOld,
      ObjectId newCommit,
      String label) {
    return () -> {
      start.await(5, TimeUnit.SECONDS);
      try (PrototypeRepository repository = database.open(repositoryName)) {
        RefUpdate.Result result =
            updateRef(repository, MAIN_REF, expectedOld, newCommit, "workflow: " + label);
        return new UpdateOutcome(isSuccess(result), result.name());
      } catch (IOException exception) {
        return new UpdateOutcome(false, exception.getMessage());
      }
    };
  }

  private static RefUpdate.Result updateRef(
      Repository repository,
      String refName,
      @Nullable ObjectId expectedOld,
      ObjectId newCommit,
      String reflogMessage)
      throws IOException {
    RefUpdate refUpdate = repository.updateRef(refName);
    refUpdate.setExpectedOldObjectId(expectedOld != null ? expectedOld : ObjectId.zeroId());
    refUpdate.setNewObjectId(newCommit);
    refUpdate.setRefLogMessage(reflogMessage, false);
    return refUpdate.update();
  }

  private static StoredCommit writeCommit(
      Repository repository,
      @Nullable ObjectId parentCommit,
      String path,
      String content,
      String message)
      throws IOException {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobId =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter treeFormatter = new TreeFormatter();
      treeFormatter.append(path, FileMode.REGULAR_FILE, blobId);
      ObjectId treeId = inserter.insert(treeFormatter);

      CommitBuilder commitBuilder = new CommitBuilder();
      commitBuilder.setAuthor(COMMITTER);
      commitBuilder.setCommitter(COMMITTER);
      commitBuilder.setMessage(message);
      commitBuilder.setTreeId(treeId);
      if (parentCommit != null) {
        commitBuilder.setParentId(parentCommit);
      }

      ObjectId commitId = inserter.insert(commitBuilder);
      inserter.flush();
      return new StoredCommit(commitId.copy(), treeId.copy(), blobId.copy());
    }
  }

  private static List<ObjectId> history(Repository repository, String refName) throws IOException {
    ObjectId head = repository.resolve(refName);
    if (head == null) {
      return List.of();
    }
    try (RevWalk revWalk = new RevWalk(repository)) {
      revWalk.markStart(revWalk.parseCommit(head));
      List<ObjectId> history = new ArrayList<>();
      for (RevCommit commit : revWalk) {
        history.add(commit.copy());
      }
      return history;
    }
  }

  private static String readBlob(Repository repository, ObjectId treeId, String path)
      throws IOException {
    try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, treeId)) {
      assertNotNull(treeWalk);
      return new String(
          repository.open(treeWalk.getObjectId(0), Constants.OBJ_BLOB).getBytes(),
          StandardCharsets.UTF_8);
    }
  }

  private static <T> T get(Future<T> result)
      throws InterruptedException, ExecutionException, TimeoutException {
    return result.get(10, TimeUnit.SECONDS);
  }

  private static List<ReflogEntry> reflogEntries(Repository repository, String refName)
      throws IOException {
    Ref ref = repository.exactRef(refName);
    assertNotNull(ref);
    ReflogReader reflogReader = repository.getRefDatabase().getReflogReader(ref);
    if (reflogReader == null) {
      return List.of();
    }
    return reflogReader.getReverseEntries();
  }

  private static boolean isSuccess(RefUpdate.Result result) {
    return result == RefUpdate.Result.FAST_FORWARD
        || result == RefUpdate.Result.NEW
        || result == RefUpdate.Result.FORCED;
  }

  private static String assertThrowsAndReturnMessage(RuntimeException exception) {
    try {
      throw exception;
    } catch (RuntimeException thrown) {
      return thrown.getMessage();
    }
  }

  private record StoredCommit(ObjectId commitId, ObjectId treeId, ObjectId blobId) {}

  private record UpdateOutcome(boolean success, String detail) {}

  private static final class SharedDatabase {
    private final ConcurrentHashMap<String, RepositoryTables> repositories =
        new ConcurrentHashMap<>();

    PrototypeRepository open(String name) {
      RepositoryTables tables = repositories.computeIfAbsent(name, RepositoryTables::new);
      return new PrototypeRepository(tables);
    }

    Set<String> repositoryNames() {
      return repositories.keySet();
    }

    List<String> allPackNames() {
      return repositories.values().stream().flatMap(tables -> tables.packNames().stream()).toList();
    }

    Set<String> distinctPackNames() {
      return Set.copyOf(allPackNames());
    }
  }

  private static final class RepositoryTables {
    private final String repositoryName;
    private final DfsRepositoryDescription description;
    private final AtomicInteger nextPackId = new AtomicInteger();
    private List<StoredPack> packs = new ArrayList<>();
    private Set<ObjectId> shallowCommits = Collections.emptySet();

    private RepositoryTables(String repositoryName) {
      this.repositoryName = repositoryName;
      this.description = new DfsRepositoryDescription(repositoryName);
    }

    synchronized List<DfsPackDescription> listPacks() {
      return new ArrayList<>(packs);
    }

    synchronized StoredPack newPack(DfsObjDatabase.PackSource source) {
      int id = nextPackId.incrementAndGet();
      return new StoredPack(
          repositoryName + "-pack-" + id + "-" + source.name(), description, source);
    }

    synchronized void commitPacks(
        Collection<DfsPackDescription> additions, Collection<DfsPackDescription> replacements)
        throws IOException {
      if (replacements != null && !packs.containsAll(replacements)) {
        throw new IOException("stale reftable replacement conflict");
      }
      List<StoredPack> next =
          additions.stream()
              .map(StoredPack.class::cast)
              .collect(Collectors.toCollection(ArrayList::new));
      next.addAll(packs);
      if (replacements != null) {
        next.removeAll(replacements);
      }
      packs = next;
    }

    synchronized ReadableChannel openFile(
        DfsPackDescription description, PackExt extension, int blockSize) throws IOException {
      StoredPack storedPack = (StoredPack) description;
      byte[] file = storedPack.get(extension);
      if (file == null) {
        throw new FileNotFoundException(description.getFileName(extension));
      }
      return new ByteArrayReadableChannel(file, blockSize);
    }

    synchronized long approximateObjectCount() {
      long count = 0;
      for (StoredPack pack : packs) {
        count += pack.getObjectCount();
      }
      return count;
    }

    synchronized Set<ObjectId> shallowCommits() {
      return shallowCommits;
    }

    synchronized void setShallowCommits(Set<ObjectId> shallowCommits) {
      this.shallowCommits = shallowCommits;
    }

    synchronized List<String> packNames() {
      return packs.stream().map(StoredPack::getPackName).filter(Objects::nonNull).toList();
    }
  }

  private static final class PrototypeRepository extends DfsRepository {
    private final RepositoryTables tables;
    private final PrototypeObjectDatabase objectDatabase;
    private final PrototypeRefDatabase refDatabase;
    private String gitwebDescription;

    PrototypeRepository(RepositoryTables tables) {
      this(new Builder().setTables(tables));
    }

    private PrototypeRepository(Builder builder) {
      super(builder);
      this.tables = builder.tables;
      this.objectDatabase = new PrototypeObjectDatabase(this, builder.tables);
      this.refDatabase = new PrototypeRefDatabase(this);
    }

    @Override
    public DfsObjDatabase getObjectDatabase() {
      return objectDatabase;
    }

    @Override
    public RefDatabase getRefDatabase() {
      return refDatabase;
    }

    @Override
    @Nullable
    public String getGitwebDescription() {
      return gitwebDescription;
    }

    @Override
    public void setGitwebDescription(@Nullable String description) {
      this.gitwebDescription = description;
    }

    @Override
    public void scanForRepoChanges() throws IOException {
      objectDatabase.refresh();
      refDatabase.refresh();
    }

    private static final class Builder extends DfsRepositoryBuilder<Builder, PrototypeRepository> {
      private RepositoryTables tables;

      private Builder setTables(RepositoryTables tables) {
        this.tables = tables;
        setRepositoryDescription(tables.description);
        return this;
      }

      @Override
      public PrototypeRepository build() throws IOException {
        return new PrototypeRepository(this);
      }
    }
  }

  private static final class PrototypeObjectDatabase extends DfsObjDatabase {
    private final RepositoryTables tables;
    private int blockSize = DEFAULT_BLOCK_SIZE;

    private PrototypeObjectDatabase(DfsRepository repository, RepositoryTables tables) {
      super(repository, new DfsReaderOptions());
      this.tables = tables;
    }

    @Override
    protected List<DfsPackDescription> listPacks() {
      return tables.listPacks();
    }

    @Override
    protected DfsPackDescription newPack(PackSource source) {
      return tables.newPack(source);
    }

    @Override
    protected void commitPackImpl(
        Collection<DfsPackDescription> additions, Collection<DfsPackDescription> replacements)
        throws IOException {
      tables.commitPacks(additions, replacements);
      clearCache();
    }

    @Override
    protected void rollbackPack(Collection<DfsPackDescription> descriptions) {
      // Object and reftable packs are published only from commitPackImpl.
    }

    @Override
    protected ReadableChannel openFile(DfsPackDescription description, PackExt extension)
        throws IOException {
      return tables.openFile(description, extension, blockSize);
    }

    @Override
    protected DfsOutputStream writeFile(DfsPackDescription description, PackExt extension) {
      StoredPack storedPack = (StoredPack) description;
      return new InMemoryPackOutputStream() {
        @Override
        protected void flush(byte[] data) {
          storedPack.put(extension, data);
        }
      };
    }

    @Override
    public Set<ObjectId> getShallowCommits() {
      return tables.shallowCommits();
    }

    @Override
    public void setShallowCommits(Set<ObjectId> shallowCommits) {
      tables.setShallowCommits(shallowCommits);
    }

    @Override
    public long getApproximateObjectCount() {
      return tables.approximateObjectCount();
    }

    private void refresh() {
      clearCache();
    }
  }

  private static final class PrototypeRefDatabase extends DfsReftableDatabase {
    private PrototypeRefDatabase(DfsRepository repository) {
      super(repository);
    }

    @Override
    public ReftableConfig getReftableConfig() {
      ReftableConfig config = new ReftableConfig();
      config.setAlignBlocks(false);
      config.setIndexObjects(false);
      config.fromConfig(getRepository().getConfig());
      return config;
    }
  }

  private static final class StoredPack extends DfsPackDescription {
    private final byte[][] fileMap = new byte[PackExt.values().length][];

    private StoredPack(
        String name, DfsRepositoryDescription description, DfsObjDatabase.PackSource source) {
      super(description, name, source);
    }

    private void put(PackExt extension, byte[] data) {
      fileMap[extension.getPosition()] = data;
    }

    private byte[] get(PackExt extension) {
      return fileMap[extension.getPosition()];
    }
  }

  private abstract static class InMemoryPackOutputStream extends DfsOutputStream {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private byte[] data;

    @Override
    public void write(byte[] buffer, int offset, int length) {
      data = null;
      output.write(buffer, offset, length);
    }

    @Override
    public int read(long position, ByteBuffer buffer) {
      byte[] bytes = data();
      if (!buffer.hasRemaining()) {
        return 0;
      }
      if (position < 0) {
        throw new IllegalArgumentException("position must be non-negative");
      }
      if (position >= bytes.length) {
        return -1;
      }
      int offset = (int) position;
      int bytesToRead = Math.min(buffer.remaining(), bytes.length - offset);
      buffer.put(bytes, offset, bytesToRead);
      return bytesToRead;
    }

    @Override
    public void close() {
      flush(data());
    }

    protected abstract void flush(byte[] data);

    private byte[] data() {
      if (data == null) {
        data = output.toByteArray();
      }
      return data;
    }
  }

  private static final class ByteArrayReadableChannel implements ReadableChannel {
    private final byte[] data;
    private final int blockSize;
    private long position;
    private boolean open = true;

    private ByteArrayReadableChannel(byte[] data, int blockSize) {
      this.data = data;
      this.blockSize = blockSize;
    }

    @Override
    public int read(ByteBuffer destination) {
      if (!destination.hasRemaining()) {
        return 0;
      }
      if (position >= data.length) {
        return -1;
      }
      int offset = Math.toIntExact(position);
      int bytesToRead = Math.min(destination.remaining(), data.length - offset);
      destination.put(data, offset, bytesToRead);
      position += bytesToRead;
      return bytesToRead;
    }

    @Override
    public void close() {
      open = false;
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public long position() {
      return position;
    }

    @Override
    public void position(long newPosition) {
      if (newPosition < 0) {
        throw new IllegalArgumentException("position must be non-negative");
      }
      if (newPosition > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("position exceeds supported range");
      }
      position = newPosition;
    }

    @Override
    public long size() {
      return data.length;
    }

    @Override
    public int blockSize() {
      return blockSize;
    }

    @Override
    public void setReadAheadBytes(int bytes) {
      // No-op for in-memory storage.
    }
  }
}
