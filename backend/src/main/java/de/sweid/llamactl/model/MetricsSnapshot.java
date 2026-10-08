package de.sweid.llamactl.model;

import java.util.List;

/**
 * Aggregate of everything the dashboard plots, refreshed once per polling tick.
 *
 * @param timestampMs server time when the snapshot was assembled
 * @param serverReachable true if llama-server's HTTP API responded
 * @param promptTokensPerSecond pp throughput (instantaneous, from llama-server)
 * @param predictedTokensPerSecond tg throughput
 * @param kvCacheUsageRatio fraction of KV cache occupied [0..1]
 * @param kvCacheTokens KV cache size in tokens
 * @param promptTokensTotal cumulative prompt tokens processed since server start
 * @param predictedTokensTotal cumulative tokens generated since server start
 * @param requestsProcessing requests currently in-flight
 * @param requestsDeferred requests queued
 * @param slots per-slot state from /slots
 * @param gpu nvidia-smi snapshot, or null if no NVIDIA GPU
 */
public record MetricsSnapshot(
    long timestampMs,
    boolean serverReachable,
    Double promptTokensPerSecond,
    Double predictedTokensPerSecond,
    Double kvCacheUsageRatio,
    Long kvCacheTokens,
    Long peakPromptTokens,
    // Millis since the last successful /metrics fetch from llama-server. null if never.
    Long metricsAgeMs,
    Long promptTokensTotal,
    Long predictedTokensTotal,
    Long requestsProcessing,
    Long requestsDeferred,
    List<SlotInfo> slots,
    GpuInfo gpu,
    CpuInfo cpu,
    RamInfo ram,
    LiveInference live) {

  /**
   * Real-time inference metrics observed by the OpenAI proxy. These are populated from completed
   * SSE streams flowing through, not from polling — so they keep updating even while llama-server's
   * HTTP loop is busy with inference.
   *
   * @param activeRequests requests currently being streamed through the proxy
   * @param requestsInWindow completed-request count in the last minute
   * @param lastPromptPerSecond pp t/s from the most recent completed request
   * @param lastPredictedPerSecond tg t/s from the most recent completed request
   * @param lastPromptTokens prompt tokens of the most recent request
   * @param lastCachedTokens cached prompt tokens (cache-hit signal)
   * @param lastCompletionTokens generated tokens count
   * @param lastCacheHitRatio cached/prompt ratio for the last request
   * @param avgCacheHitRatio average cached/prompt ratio across the window
   * @param totalTokensEvaluatedInWindow sum of (prompt - cached) — tokens actually processed
   * @param totalGeneratedInWindow sum of completion_tokens across the window
   * @param lastObservedAgeMs millis since the most recent observation finished (null if none)
   */
  public record LiveInference(
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

  /**
   * CPU snapshot derived from {@code /proc/stat} deltas.
   *
   * @param utilizationPercent overall CPU busy %, 0–100
   * @param coreCount number of logical cores
   * @param loadAverage1m 1-minute load average from {@code /proc/loadavg}
   */
  public record CpuInfo(Integer utilizationPercent, Integer coreCount, Double loadAverage1m) {}

  /**
   * Host RAM snapshot from {@code /proc/meminfo}.
   *
   * @param usedMb (MemTotal − MemAvailable) / 1024
   * @param totalMb MemTotal / 1024
   */
  public record RamInfo(Long usedMb, Long totalMb) {}

  /**
   * Per-slot KV cache state — what {@code /slots} reports.
   *
   * @param id slot id
   * @param isProcessing whether a request is currently using this slot
   * @param pastTokens tokens already in this slot's KV cache (high = cache reuse working)
   * @param promptTokens last request's prompt token count
   * @param predictedTokens last request's generated token count
   */
  public record SlotInfo(
      int id,
      boolean isProcessing,
      Long pastTokens,
      Long contextSize,
      Long promptTokens,
      Long predictedTokens) {}

  /**
   * GPU snapshot — one row per detected NVIDIA GPU.
   *
   * @param name GPU name
   * @param utilizationPercent GPU compute utilisation 0–100
   * @param memoryUsedMb VRAM used in MB
   * @param memoryTotalMb total VRAM in MB
   * @param temperatureC core temperature in celsius
   * @param powerW current power draw in watts
   */
  public record GpuInfo(
      String name,
      Integer utilizationPercent,
      Integer memoryUsedMb,
      Integer memoryTotalMb,
      Integer temperatureC,
      Double powerW) {}
}
