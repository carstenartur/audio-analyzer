package org.hammer.audio.acquisition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Directory-backed profile store using the deterministic profile codec. */
public final class DirectoryMicrophoneArrayProfileStore implements MicrophoneArrayProfileStore {

  private static final String FILE_SUFFIX = ".maprofile";

  private final Path directory;
  private final MicrophoneArrayProfileCodec codec;

  /** Create a store rooted at one directory. */
  public DirectoryMicrophoneArrayProfileStore(Path directory) {
    this(directory, new MicrophoneArrayProfileCodec());
  }

  /** Create a store with an explicit codec. */
  public DirectoryMicrophoneArrayProfileStore(Path directory, MicrophoneArrayProfileCodec codec) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  public void save(MicrophoneArrayProfile profile) throws IOException {
    Objects.requireNonNull(profile, "profile");
    Files.createDirectories(directory);
    Path target = pathFor(profile.profileId());
    Path temporary = Files.createTempFile(directory, "profile-", ".tmp");
    try {
      Files.writeString(temporary, codec.encode(profile), StandardCharsets.UTF_8);
      moveAtomicallyWhenSupported(temporary, target);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public Optional<MicrophoneArrayProfile> find(String profileId) throws IOException {
    Path path = pathFor(profileId);
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    return Optional.of(codec.decode(Files.readString(path, StandardCharsets.UTF_8)));
  }

  @Override
  public List<MicrophoneArrayProfile> list() throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.list(directory)) {
      return paths
          .filter(DirectoryMicrophoneArrayProfileStore::isProfileFile)
          .map(this::readUnchecked)
          .sorted(Comparator.comparing(MicrophoneArrayProfile::profileId))
          .toList();
    } catch (UncheckedIOException exception) {
      throw exception.getCause();
    }
  }

  @Override
  public boolean delete(String profileId) throws IOException {
    return Files.deleteIfExists(pathFor(profileId));
  }

  private MicrophoneArrayProfile readUnchecked(Path path) {
    try {
      return codec.decode(Files.readString(path, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private Path pathFor(String profileId) {
    if (profileId == null || profileId.isBlank()) {
      throw new IllegalArgumentException("profileId must not be blank");
    }
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(profileId.getBytes(StandardCharsets.UTF_8));
    return directory.resolve(encoded + FILE_SUFFIX);
  }

  private static boolean isProfileFile(Path path) {
    Path fileName = path.getFileName();
    return fileName != null && fileName.toString().endsWith(FILE_SUFFIX);
  }

  private static void moveAtomicallyWhenSupported(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
