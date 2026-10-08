package de.sweid.llamactl.service;

import de.sweid.llamactl.config.LlamaControlProperties;
import de.sweid.llamactl.model.ModelFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Scans the configured directories for {@code .gguf} files. */
@Service
public class ModelScannerService {

  private static final Logger LOG = LoggerFactory.getLogger(ModelScannerService.class);
  private static final String GGUF_SUFFIX = ".gguf";

  private final LlamaControlProperties props;

  /**
   * Wires the configured directories.
   *
   * @param props bound config
   */
  public ModelScannerService(LlamaControlProperties props) {
    this.props = props;
  }

  /**
   * Walks every configured {@code model-dir} and returns the .gguf files it finds. Directories
   * that don't exist are silently skipped (logged at warn).
   *
   * @return discovered models, sorted by display name
   */
  public List<ModelFile> scan() {
    return props.getModelDirs().stream()
        .map(Path::of)
        .flatMap(this::walk)
        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(GGUF_SUFFIX))
        .map(this::toModelFile)
        .sorted(Comparator.comparing(ModelFile::name))
        .toList();
  }

  private Stream<Path> walk(Path dir) {
    if (!Files.isDirectory(dir)) {
      LOG.warn("Model dir does not exist: {}", dir);
      return Stream.empty();
    }
    try {
      return Files.walk(dir);
    } catch (IOException e) {
      LOG.warn("Failed to walk {}: {}", dir, e.toString());
      return Stream.empty();
    }
  }

  private ModelFile toModelFile(Path path) {
    long size = 0L;
    try {
      size = Files.size(path);
    } catch (IOException ignored) {
      // best-effort
    }
    String name = path.getParent().getFileName() + "/" + path.getFileName();
    return new ModelFile(name, path.toString(), size);
  }
}
