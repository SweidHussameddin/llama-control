package de.sweid.llamactl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.sweid.llamactl.config.LlamaControlProperties;
import de.sweid.llamactl.model.Profile;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Profile CRUD backed by one JSON file per profile under {@link
 * LlamaControlProperties#getProfilesDir()}. Filenames are {@code <id>.json}.
 */
@Service
public class ProfileService {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileService.class);
  private static final String JSON_SUFFIX = ".json";

  private final LlamaControlProperties props;
  private final ObjectMapper mapper;
  private Path dir;

  /**
   * Wires the persistence directory and JSON mapper.
   *
   * @param props bound config
   * @param mapper Spring-managed Jackson mapper
   */
  public ProfileService(LlamaControlProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
  }

  @PostConstruct
  void init() throws IOException {
    dir = Path.of(props.getProfilesDir());
    Files.createDirectories(dir);
    LOG.info("Profiles dir: {}", dir);
  }

  /**
   * Lists every profile stored on disk.
   *
   * @return profiles sorted by display name
   */
  public List<Profile> list() {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(JSON_SUFFIX))
          .map(this::readSilently)
          .flatMap(Optional::stream)
          .sorted(Comparator.comparing(Profile::name))
          .toList();
    } catch (IOException e) {
      LOG.warn("Failed to list profiles: {}", e.toString());
      return List.of();
    }
  }

  /**
   * Loads a single profile by id.
   *
   * @param id profile id (the filename stem)
   * @return the profile if present
   */
  public Optional<Profile> get(String id) {
    return readSilently(pathFor(id));
  }

  /**
   * Creates or replaces a profile by id.
   *
   * @param profile the profile to persist
   * @return the persisted profile (round-tripped through JSON)
   * @throws IOException on disk failure
   */
  public Profile save(Profile profile) throws IOException {
    Path target = pathFor(profile.id());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), profile);
    LOG.info("Saved profile {} to {}", profile.id(), target);
    return profile;
  }

  /**
   * Deletes a profile by id.
   *
   * @param id profile id
   * @return true if a file was removed
   * @throws IOException on disk failure
   */
  public boolean delete(String id) throws IOException {
    return Files.deleteIfExists(pathFor(id));
  }

  private Path pathFor(String id) {
    return dir.resolve(id + JSON_SUFFIX);
  }

  private Optional<Profile> readSilently(Path path) {
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(path.toFile(), Profile.class));
    } catch (IOException e) {
      LOG.warn("Failed to read profile {}: {}", path, e.toString());
      return Optional.empty();
    }
  }
}
