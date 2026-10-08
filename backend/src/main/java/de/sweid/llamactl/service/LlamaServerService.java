package de.sweid.llamactl.service;

import de.sweid.llamactl.config.LlamaControlProperties;
import de.sweid.llamactl.model.LlamaFlags;
import de.sweid.llamactl.model.ModelDetails;
import de.sweid.llamactl.model.Profile;
import de.sweid.llamactl.model.ServerStatus;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the lifecycle of the supervised {@code llama-server} child process: starts it from a
 * rendered {@link LlamaFlags}, drains its merged stdout/stderr into the shared {@link LogBuffer},
 * and stops it cleanly on request or JVM shutdown. Exactly one process is supervised at a time.
 */
@Service
public class LlamaServerService {

  private static final Logger LOG = LoggerFactory.getLogger(LlamaServerService.class);
  private static final long STOP_GRACE_SECONDS = 10L;

  private final LlamaControlProperties props;
  private final FlagsRenderer renderer;
  private final LogBuffer logBuffer;
  private final GgufReader ggufReader;

  private volatile Process process;
  private volatile List<String> command = List.of();
  private volatile String profileId;
  private volatile LlamaFlags startedFlags;
  private volatile Long startedAtEpochMs;
  private volatile Integer exitCode;
  private volatile String baseUrl;

  /**
   * Wires dependencies.
   *
   * @param props bound config
   * @param renderer turns flags into argv
   * @param logBuffer shared log destination
   * @param ggufReader reads model architecture for flags that need it (e.g. {@code
   *     --override-kv})
   */
  public LlamaServerService(
      LlamaControlProperties props,
      FlagsRenderer renderer,
      LogBuffer logBuffer,
      GgufReader ggufReader) {
    this.props = props;
    this.renderer = renderer;
    this.logBuffer = logBuffer;
    this.ggufReader = ggufReader;
  }

  /**
   * Starts llama-server with the flags from the given profile.
   *
   * @param profile the profile to launch
   * @return resulting status
   * @throws IOException if the process fails to launch
   * @throws IllegalStateException if a process is already running
   */
  public synchronized ServerStatus start(Profile profile) throws IOException {
    if (isRunning()) {
      throw new IllegalStateException("A llama-server process is already running");
    }
    LlamaFlags flags = profile.flags();
    List<String> argv = new ArrayList<>(renderer.render(flags));

    // Inject --override-kv <arch>.expert_used_count=int:N when the user set
    // expertsUsed and we know the model's architecture from the GGUF header.
    if (flags.expertsUsed() != null && flags.modelPath() != null) {
      try {
        ModelDetails md = ggufReader.read(Path.of(flags.modelPath()));
        if (md.architecture() != null) {
          argv.add("--override-kv");
          argv.add(md.architecture() + ".expert_used_count=int:" + flags.expertsUsed());
        } else {
          LOG.warn(
              "expertsUsed={} requested but architecture unknown — skipping --override-kv",
              flags.expertsUsed());
        }
      } catch (IOException e) {
        LOG.warn(
            "Could not read GGUF for expertsUsed override ({}): {}",
            flags.modelPath(),
            e.toString());
      }
    }

    List<String> full = new ArrayList<>();
    full.add(Objects.requireNonNull(props.getBinary(), "llama-control.binary not configured"));
    full.addAll(argv);

    logBuffer.clear();
    logBuffer.append("$ " + String.join(" ", full));

    ProcessBuilder pb = new ProcessBuilder(full).redirectErrorStream(true);
    Process p = pb.start();
    this.process = p;
    this.command = full;
    this.profileId = profile.id();
    this.startedFlags = flags;
    this.startedAtEpochMs = System.currentTimeMillis();
    this.exitCode = null;
    // The baseUrl we ADVERTISE is our own proxy — clients (OpenCode etc.) should connect to
    // us, not directly to llama-server, so we can observe their streams for live telemetry.
    // The actual upstream llama-server is reachable via the OpenAIProxyController internally.
    this.baseUrl = props.getProxyBaseUrl();

    Thread reader = new Thread(() -> drain(p), "llama-server-log-reader");
    reader.setDaemon(true);
    reader.start();

    p.onExit()
        .thenAccept(
            done -> {
              this.exitCode = done.exitValue();
              logBuffer.append("[llama-control] process exited with code " + done.exitValue());
              LOG.info("llama-server exited with code {}", done.exitValue());
            });

    LOG.info("Started llama-server pid={} profile={}", p.pid(), profile.id());
    return status();
  }

  /**
   * Stops the supervised process if running. Idempotent.
   *
   * @return resulting status
   */
  public synchronized ServerStatus stop() {
    Process p = this.process;
    if (p == null || !p.isAlive()) {
      return status();
    }
    LOG.info("Stopping llama-server pid={}", p.pid());
    p.destroy();
    try {
      if (!p.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
        LOG.warn("llama-server did not exit gracefully after {}s; killing", STOP_GRACE_SECONDS);
        p.destroyForcibly();
        p.waitFor(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return status();
  }

  /**
   * Returns the current status snapshot.
   *
   * @return status
   */
  public synchronized ServerStatus status() {
    Process p = this.process;
    boolean running = p != null && p.isAlive();
    long pid = running ? p.pid() : -1L;
    return new ServerStatus(
        running,
        pid,
        profileId,
        running ? startedFlags : null,
        command,
        startedAtEpochMs,
        exitCode,
        baseUrl);
  }

  /**
   * Returns whether a process is currently alive.
   *
   * @return true if the supervised process is alive
   */
  public boolean isRunning() {
    Process p = this.process;
    return p != null && p.isAlive();
  }

  /**
   * Returns the actual upstream llama-server URL (port 8080 by default), used by internal
   * services that need to talk to llama-server directly — the metrics poller and the proxy
   * controller. This is distinct from {@link ServerStatus#baseUrl()}, which advertises the
   * proxy URL (port 8090) to external clients.
   *
   * @return e.g. {@code http://127.0.0.1:8080}
   */
  public String upstreamBaseUrl() {
    String host = props.getDefaultServerHost();
    int port = props.getDefaultServerPort();
    if (startedFlags != null) {
      if (startedFlags.host() != null) {
        host = startedFlags.host();
      }
      if (startedFlags.port() != null) {
        port = startedFlags.port();
      }
    }
    return "http://" + host + ":" + port;
  }

  /** Best-effort shutdown if the JVM stops while a process is running. */
  @PreDestroy
  public void shutdown() {
    stop();
  }

  private void drain(Process p) {
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        logBuffer.append(line);
      }
    } catch (IOException e) {
      LOG.warn("Log reader failed: {}", e.toString());
    }
  }

  private static <T> T valueOrDefault(T value, T fallback) {
    return value == null ? fallback : value;
  }
}
