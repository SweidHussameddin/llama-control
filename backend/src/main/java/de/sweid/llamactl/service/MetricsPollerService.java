package de.sweid.llamactl.service;

import de.sweid.llamactl.config.LlamaControlProperties;
import de.sweid.llamactl.model.MetricsSnapshot;
import de.sweid.llamactl.model.MetricsSnapshot.CpuInfo;
import de.sweid.llamactl.model.MetricsSnapshot.GpuInfo;
import de.sweid.llamactl.model.MetricsSnapshot.LiveInference;
import de.sweid.llamactl.model.MetricsSnapshot.RamInfo;
import de.sweid.llamactl.model.MetricsSnapshot.SlotInfo;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Publishes telemetry snapshots to subscribers (SSE) on a fast tick that NEVER blocks on HTTP.
 * llama-server's HTTP loop serialises behind inference, so any polling against it can stall for
 * many seconds; we run that polling on a separate, slower cadence and cache the results. The fast
 * publisher then assembles snapshots from the in-memory cache plus the always-fast observer/GPU
 * state.
 *
 * <p>The two schedules need to run concurrently — see {@code spring.task.scheduling.pool.size}.
 */
@Service
public class MetricsPollerService {

  private final LlamaServerService server;
  private final LlamaMetricsClient metricsClient;
  private final GpuMonitorService gpu;
  private final SystemMonitorService host;
  private final RequestObserverService observer;
  private final LlamaControlProperties props;

  // === HTTP-derived cache (populated by pollHttp, read by publishSnapshot) ===
  private volatile Double cachedPp;
  private volatile Double cachedTg;
  private volatile Long cachedPromptTotal;
  private volatile Long cachedPredictedTotal;
  private volatile Long cachedProcessing;
  private volatile Long cachedDeferred;
  private volatile Long cachedPeakPrompt;
  private volatile List<SlotInfo> cachedSlots = List.of();
  private volatile Double cachedKvRatio;
  private volatile Long cachedKvTokens;
  private volatile long lastHttpFetchAtMs;
  private volatile boolean lastReachable;

  private volatile MetricsSnapshot latest =
      new MetricsSnapshot(
          System.currentTimeMillis(),
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          List.of(),
          null,
          null,
          null,
          null);

  private final java.util.List<Consumer<MetricsSnapshot>> subscribers = new java.util.ArrayList<>();

  /**
   * Wires dependencies.
   *
   * @param server process supervisor
   * @param metricsClient HTTP client to llama-server
   * @param gpu GPU monitor
   * @param host CPU/RAM monitor
   * @param observer proxy request observer
   * @param props bound config (for default host/port when no process is running)
   */
  public MetricsPollerService(
      LlamaServerService server,
      LlamaMetricsClient metricsClient,
      GpuMonitorService gpu,
      SystemMonitorService host,
      RequestObserverService observer,
      LlamaControlProperties props) {
    this.server = server;
    this.metricsClient = metricsClient;
    this.gpu = gpu;
    this.host = host;
    this.observer = observer;
    this.props = props;
  }

  /**
   * Slow tick — does the network-bound work. Runs on its own scheduler thread; can stall for many
   * seconds while llama-server is busy decoding without blocking snapshot publishing.
   */
  @Scheduled(fixedDelay = 3000L)
  public void pollHttp() {
    String baseUrl = server.upstreamBaseUrl() + "/v1";
    boolean reachable = server.isRunning() && metricsClient.ping(baseUrl);
    lastReachable = reachable;

    if (!reachable) {
      cachedPp = null;
      cachedTg = null;
      cachedPromptTotal = null;
      cachedPredictedTotal = null;
      cachedProcessing = null;
      cachedDeferred = null;
      cachedPeakPrompt = null;
      cachedSlots = List.of();
      cachedKvRatio = null;
      cachedKvTokens = null;
      lastHttpFetchAtMs = 0L;
      return;
    }

    boolean fetched = false;

    Map<String, Double> m = metricsClient.fetchMetrics(baseUrl);
    if (!m.isEmpty()) {
      cachedPp = m.get("llamacpp:prompt_tokens_seconds");
      cachedTg = m.get("llamacpp:predicted_tokens_seconds");
      cachedPromptTotal = toLong(m.get("llamacpp:prompt_tokens_total"));
      cachedPredictedTotal = toLong(m.get("llamacpp:tokens_predicted_total"));
      cachedProcessing = toLong(m.get("llamacpp:requests_processing"));
      cachedDeferred = toLong(m.get("llamacpp:requests_deferred"));
      cachedPeakPrompt = toLong(m.get("llamacpp:n_tokens_max"));
      fetched = true;
    }

    List<SlotInfo> slots = metricsClient.fetchSlots(baseUrl);
    if (!slots.isEmpty()) {
      cachedSlots = slots;
      long pastSum = 0L;
      long ctxSum = 0L;
      boolean anyPast = false;
      for (SlotInfo s : slots) {
        if (s.pastTokens() != null) {
          pastSum += s.pastTokens();
          anyPast = true;
        }
        if (s.contextSize() != null) {
          ctxSum += s.contextSize();
        }
      }
      if (anyPast) {
        cachedKvTokens = pastSum;
        if (ctxSum > 0) {
          cachedKvRatio = (double) pastSum / (double) ctxSum;
        }
      }
      fetched = true;
    }

    if (fetched) {
      lastHttpFetchAtMs = System.currentTimeMillis();
    }
  }

  /**
   * Fast tick — assembles a snapshot from the cache + the observer + GPU/CPU/RAM and broadcasts
   * it. Never touches HTTP, so it runs at a tight cadence even while llama-server is unresponsive.
   */
  @Scheduled(fixedDelay = 500L)
  public void publishSnapshot() {
    GpuInfo g = gpu.snapshot();
    CpuInfo c = host.cpu();
    RamInfo r = host.ram();

    Long ageMs = lastHttpFetchAtMs > 0 ? System.currentTimeMillis() - lastHttpFetchAtMs : null;

    RequestObserverService.LiveMetrics lm = observer.snapshot();
    LiveInference live =
        new LiveInference(
            lm.activeRequests(),
            lm.requestsInWindow(),
            lm.inFlightPredictedPerSecond(),
            lm.lastPromptPerSecond(),
            lm.lastPredictedPerSecond(),
            lm.lastPromptTokens(),
            lm.lastCachedTokens(),
            lm.lastCompletionTokens(),
            lm.lastCacheHitRatio(),
            lm.avgCacheHitRatio(),
            lm.totalTokensEvaluatedInWindow(),
            lm.totalGeneratedInWindow(),
            lm.lastObservedAgeMs());

    MetricsSnapshot snap =
        new MetricsSnapshot(
            System.currentTimeMillis(),
            lastReachable,
            cachedPp,
            cachedTg,
            cachedKvRatio,
            cachedKvTokens,
            cachedPeakPrompt,
            ageMs,
            cachedPromptTotal,
            cachedPredictedTotal,
            cachedProcessing,
            cachedDeferred,
            cachedSlots,
            g,
            c,
            r,
            live);
    this.latest = snap;
    broadcast(snap);
  }

  /**
   * Returns the most recently published snapshot.
   *
   * @return last snapshot
   */
  public MetricsSnapshot latest() {
    return latest;
  }

  /**
   * Subscribes for live snapshot pushes.
   *
   * @param sink consumer to receive snapshots
   * @return unsubscribe handle
   */
  public synchronized Runnable subscribe(Consumer<MetricsSnapshot> sink) {
    subscribers.add(sink);
    return () -> {
      synchronized (MetricsPollerService.this) {
        subscribers.remove(sink);
      }
    };
  }

  private synchronized void broadcast(MetricsSnapshot snap) {
    for (Consumer<MetricsSnapshot> s : new java.util.ArrayList<>(subscribers)) {
      try {
        s.accept(snap);
      } catch (RuntimeException ignored) {
        // subscriber will be reaped lazily on next throw
      }
    }
  }

  // baseUrl helper removed — pollHttp() reads from server.upstreamBaseUrl directly. props kept
  // for the @Component bind but unused at the moment.
  @SuppressWarnings("unused")
  private LlamaControlProperties unused() {
    return props;
  }

  private static Long toLong(Double d) {
    return d == null ? null : d.longValue();
  }
}
