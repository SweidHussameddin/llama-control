package de.sweid.llamactl.api;

import de.sweid.llamactl.model.ModelDetails;
import de.sweid.llamactl.model.ModelFile;
import de.sweid.llamactl.service.GgufReader;
import de.sweid.llamactl.service.ModelScannerService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the discovered {@code .gguf} files for the launcher's model picker. */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

  private final ModelScannerService scanner;
  private final GgufReader reader;

  /**
   * Wires the scanner and metadata reader.
   *
   * @param scanner the model scanner service
   * @param reader GGUF metadata reader
   */
  public ModelsController(ModelScannerService scanner, GgufReader reader) {
    this.scanner = scanner;
    this.reader = reader;
  }

  /**
   * Returns all {@code .gguf} files found under the configured model directories.
   *
   * @return the model list
   */
  @GetMapping
  public List<ModelFile> list() {
    return scanner.scan();
  }

  /**
   * Returns parsed GGUF metadata for a specific file. Looked up by absolute path passed as a
   * query parameter so the frontend doesn't need URL-encoding gymnastics.
   *
   * @param path absolute path of the .gguf file
   * @return parsed details, or 404 if the parser couldn't read the file
   */
  @GetMapping("/details")
  public ResponseEntity<ModelDetails> details(@RequestParam("path") String path) {
    try {
      return ResponseEntity.ok(reader.read(Path.of(path)));
    } catch (IOException e) {
      return ResponseEntity.notFound().build();
    }
  }
}
