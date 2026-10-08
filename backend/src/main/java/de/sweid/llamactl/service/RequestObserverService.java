package de.sweid.llamactl.service;

import de.sweid.llamactl.model.RequestObservation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maintains a rolling window of {@link RequestObservation}s collected by the OpenAI proxy. Also
 * tracks per-active-stream state (chunk count, time of first chunk) so the dashboard can show a
 * live tok/s rate while a request is mid-flight, not just after it completes.
 */
@Service
public class RequestObserverService {

  private static final Logger LOG = LoggerFactory.getLogger(RequestObserverService.class);
  private static final long WINDOW_MS = 60_000L;
  private static final int MAX_HISTORY = 200;

  /**
   * Snapshot of recently observed inference metrics. All fields nullable if no data in window.
   *
   * @param activeRequests number of requests the proxy is currently streaming
   * @param requestsInWindow number of completed requests in the last minute
   * @param inFlightPredictedPerSecond live tok/s for the in-flight request — chunks/elapsed since
   *     the first chunk arrived; null if no stream is active or too few chunks yet
   * @param lastPromptPerSecond pp t/s from the most recent completed request
   * @param lastPredictedPerSecond tg t/s from the most recent completed request
   * @param lastPromptTokens prompt size of the most recent completed request
   * @param lastCachedTokens cached prompt tokens of the most recent completed request
   * @param lastCompletionTokens generation length of the most recent completed request
   * @param lastCacheHitRatio cached/prompt ratio for the most recent completed request
   * @param avgCacheHitRatio average cached/prompt ratio across the window
   * @param totalTokensEvaluatedInWindow sum of (prompt - cached) across window — i.e. how many
   *     prompt tokens llama-server actually processed in the last minute
   * @param totalGeneratedInWindow sum of completion_tokens across the window
   * @param lastObservedAgeMs millis since the most recent observation finished (null if none)
   */
  public record LiveMetrics(
      int activeRequests,
      int requestsInWindow,
      Double inFlightPredictedPerSecond,
      Double lastPromptPerSecond,
      Double lastPredictedPerSecond,
      Integer lastPromptTokens,
      Integer lastCachedTokens,
      Integer lastCompletionTokens,
      Double lastCacheHitRatio,
      Double avgCacheHitRatio,
      Long totalTokensEvaluatedInWindow,
      Long totalGeneratedInWindow,
      Long lastObservedAgeMs) {}

  /** Mutable per-active-stream stats — only accessed under the service-level monitor. */
  private static final class ActiveStream {
    final long startedAtMs;
    long firstChunkAtMs = -1L;
    long lastChunkAtMs = -1L;
    int chunks = 0;

    // Sliding-window samples for instantaneous rate. Each entry is (timestampMs, chunkCount).
    // Sampled by snapshot() on each tick (every ~500 ms). Trimmed to the last 3 seconds —
    // gives a real "tok/s right now" instead of the cumulative average since the request
    // started (which drifts downward every time the generation pauses for a millisecond).
    final ArrayDeque<long[]> samples = new ArrayDeque<>();

    // llama-server's own reported predicted_per_second from the most recent SSE chunk's
    // timings block (only present when `timings_per_token: true` is set on the request,
    // which the proxy injects automatically). This is the authoritative source — exact
    // and current — when available. We fall back to chunk-based sliding-window estimation
    // only if llama-server isn't reporting it.
    Double reportedRate = null;
    long reportedRateAtMs = -1L;

    ActiveStream(long startedAtMs) {
      this.startedAtMs = startedAtMs;
    }
  }

  private final Deque<RequestObservation> recent = new ArrayDeque<>();
  private final Map<Long, ActiveStream> activeStreams = new HashMap<>();
  private final AtomicLong nextId = new AtomicLong();

  /**
   * Registers a new active request (streaming or non-streaming) and returns an id the proxy uses
   * to attribute subsequent chunks and the eventual completion.
   *
   * @return opaque stream id
   */
  public synchronized long startRequest() {
    long id = nextId.incrementAndGet();
    activeStreams.put(id, new ActiveStream(System.currentTimeMillis()));
    return id;
  }

  /**
   * Records llama-server's own {@code timings.predicted_per_second} value reported in a streamed
   * chunk. Preferred over chunk-counting because it's the exact number llama-server is computing
   * internally and isn't fooled by multi-token SSE chunks.
   *
   * @param id stream id returned from {@link #startRequest()}
   * @param rate tokens/second value from the chunk's timings block
   */
  public synchronized void observeReportedRate(long id, double rate) {
    ActiveStream s = activeStreams.get(id);
    if (s == null) {
      return;
    }
    s.reportedRate = rate;
    s.reportedRateAtMs = System.currentTimeMillis();
  }

  /**
   * Records that a content chunk was observed for the given stream. Used to compute live tok/s
   * during the request.
   *
   * @param id stream id returned from {@link #startRequest()}
   */
  public synchronized void observeChunk(long id) {
    ActiveStream s = activeStreams.get(id);
    if (s == null) {
      return;
    }
    long now = System.currentTimeMillis();
    if (s.firstChunkAtMs == -1L) {
      s.firstChunkAtMs = now;
    }
    s.lastChunkAtMs = now;
    s.chunks++;
  }

  /**
   * Completes the request: removes the active stream entry and adds the final observation to the
   * rolling window.
   *
   * @param id stream id returned from {@link #startRequest()}
   * @param obs the assembled observation (timings, usage, etc.)
   */
  public synchronized void endRequest(long id, RequestObservation obs) {
    activeStreams.remove(id);
    recent.addLast(obs);
    if (LOG.isDebugEnabled()) {
      Double genSecs = obs.generationSeconds();
      LOG.debug(
          "Request ended: chunks={} promptTokens={} cachedTokens={} completionTokens={} "
              + "predictedPerSecond={} genSecs={}",
          obs.chunkCount(),
          obs.promptTokens(),
          obs.cachedTokens(),
          obs.completionTokens(),
          obs.predictedPerSecond(),
          genSecs);
    }
    while (recent.size() > MAX_HISTORY) {
      recent.removeFirst();
    }
    long cutoff = System.currentTimeMillis() - WINDOW_MS;
    while (!recent.isEmpty() && recent.peekFirst().finishedAtMs() < cutoff) {
      recent.removeFirst();
    }
  }

  /** Returns the derived live metrics snapshot. */
  public synchronized LiveMetrics snapshot() {
    long now = System.currentTimeMillis();
    long cutoff = now - WINDOW_MS;
    List<RequestObservation> inWindow = new ArrayList<>();
    for (RequestObservation o : recent) {
      if (o.finishedAtMs() >= cutoff) {
        inWindow.add(o);
      }
    }

    final RequestObservation last =
        inWindow.isEmpty() ? null : inWindow.get(inWindow.size() - 1);

    Double avgCacheHit = null;
    long totalEvaluated = 0L;
    long totalGenerated = 0L;
    int cacheSamples = 0;
    double cacheSum = 0.0;
    for (RequestObservation o : inWindow) {
      Double r = o.cacheHitRatio();
      if (r != null) {
        cacheSum += r;
        cacheSamples++;
      }
      if (o.promptTokens() != null) {
        int cached = o.cachedTokens() == null ? 0 : o.cachedTokens();
        totalEvaluated += Math.max(0, o.promptTokens() - cached);
      }
      if (o.completionTokens() != null) {
        totalGenerated += o.completionTokens();
      }
    }
    if (cacheSamples > 0) {
      avgCacheHit = cacheSum / cacheSamples;
    }

    // In-flight tok/s — prefer llama-server's own predicted_per_second from timings (set via
    // the timings_per_token request flag). Fall back to chunk-based sliding-window estimation
    // when that's not available.
    Double inFlightRate = null;
    for (ActiveStream s : activeStreams.values()) {
      if (s.firstChunkAtMs <= 0) {
        continue;
      }

      // Path 1: llama-server reports the exact rate in each chunk
      if (s.reportedRate != null && now - s.reportedRateAtMs < 3000L) {
        if (inFlightRate == null || s.reportedRate > inFlightRate) {
          inFlightRate = s.reportedRate;
        }
        continue;
      }

      // Path 2: chunk-based fallback (sliding 3 s window)
      s.samples.addLast(new long[] {now, s.chunks});
      long sampleCutoff = now - 3000L;
      while (s.samples.size() > 1 && s.samples.peekFirst()[0] < sampleCutoff) {
        s.samples.removeFirst();
      }
      if (now - s.lastChunkAtMs > 2000L) {
        continue;
      }
      if (s.samples.size() < 2) {
        continue;
      }
      long[] oldest = s.samples.peekFirst();
      long[] newest = s.samples.peekLast();
      long deltaMs = newest[0] - oldest[0];
      long chunkDelta = newest[1] - oldest[1];
      if (deltaMs < 200L || chunkDelta <= 0) {
        continue;
      }
      double rate = chunkDelta * 1000.0 / deltaMs;
      if (inFlightRate == null || rate > inFlightRate) {
        inFlightRate = rate;
      }
    }

    Long lastAge = null;
    if (last != null) {
      lastAge = now - last.finishedAtMs();
    }

    return new LiveMetrics(
        activeStreams.size(),
        inWindow.size(),
        inFlightRate,
        last == null ? null : last.promptPerSecond(),
        last == null ? null : last.predictedPerSecond(),
        last == null ? null : last.promptTokens(),
        last == null ? null : last.cachedTokens(),
        last == null ? null : last.completionTokens(),
        last == null ? null : last.cacheHitRatio(),
        avgCacheHit,
        totalEvaluated,
        totalGenerated,
        lastAge);
  }
}
