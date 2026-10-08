package de.sweid.llamactl.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness probe so the frontend can confirm the backend is reachable. */
@RestController
@RequestMapping("/api")
public class HealthController {

  /**
   * Returns a static "ok" payload.
   *
   * @return service identity and status
   */
  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok", "service", "llama-control");
  }
}
