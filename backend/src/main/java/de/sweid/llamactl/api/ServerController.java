package de.sweid.llamactl.api;

import de.sweid.llamactl.model.Profile;
import de.sweid.llamactl.model.ServerStatus;
import de.sweid.llamactl.service.LlamaServerService;
import de.sweid.llamactl.service.ProfileService;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Process lifecycle endpoints — start, stop, status. */
@RestController
@RequestMapping("/api/server")
public class ServerController {

  private final LlamaServerService server;
  private final ProfileService profiles;

  /**
   * Wires services.
   *
   * @param server process supervisor
   * @param profiles profile store
   */
  public ServerController(LlamaServerService server, ProfileService profiles) {
    this.server = server;
    this.profiles = profiles;
  }

  /**
   * Returns current process status.
   *
   * @return status
   */
  @GetMapping("/status")
  public ServerStatus status() {
    return server.status();
  }

  /**
   * Starts llama-server using the profile referenced by id (loaded from disk).
   *
   * @param id profile id
   * @return resulting status, or 404 if profile is missing, or 409 if already running
   * @throws IOException on process launch failure
   */
  @PostMapping("/start/{id}")
  public ResponseEntity<ServerStatus> startByProfileId(@PathVariable String id) throws IOException {
    return profiles
        .get(id)
        .map(this::startSafely)
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * Starts llama-server using a profile provided inline (not necessarily saved).
   *
   * @param profile the profile to launch
   * @return resulting status, or 409 if already running
   * @throws IOException on process launch failure
   */
  @PostMapping("/start")
  public ResponseEntity<ServerStatus> startInline(@RequestBody Profile profile) throws IOException {
    return startSafely(profile);
  }

  /**
   * Stops the supervised process if running.
   *
   * @return resulting status
   */
  @PostMapping("/stop")
  public ServerStatus stop() {
    return server.stop();
  }

  private ResponseEntity<ServerStatus> startSafely(Profile profile) {
    try {
      return ResponseEntity.ok(server.start(profile));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    } catch (IOException e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
