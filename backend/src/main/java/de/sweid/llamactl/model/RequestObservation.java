package de.sweid.llamactl.model;

/**
 * What we observed about a single completion request as its SSE stream (or JSON response) passed
 * through our proxy. Captures both timing we measured ourselves and the structured timings/usage
 * llama-server reports in its final chunk.
 *
 * @param endpoint relative URI of the request (e.g. {@code /v1/chat/completions})
 * @param startedAtMs wall-clock millis when the proxy received the request
 * @param firstTokenAtMs wall-clock millis when the first content chunk was forwarded (-1 if none)
 * @param finishedAtMs wall-clock millis when the upstream closed the response
 * @param promptTokens prompt tokens reported by llama-server's {@code usage.prompt_tokens}
 * @param cachedTokens cached prompt tokens from {@code usage.prompt_tokens_details.cached_tokens}
 * @param completionTokens generated tokens from {@code usage.completion_tokens}
 * @param promptPerSecond pp rate from {@code timings.prompt_per_second}
 * @param predictedPerSecond tg rate from {@code timings.predicted_per_second}
 * @param promptMs prompt-eval wall time from {@code timings.prompt_ms}
 * @param predictedMs generation wall time from {@code timings.predicted_ms}
 * @param chunkCount number of SSE data chunks we forwarded (lower-bound on token count)
 * @param errored true if the upstream returned a non-2xx status or the stream errored
 */
public record RequestObservation(
    String endpoint,
    long startedAtMs,
    long firstTokenAtMs,
    long finishedAtMs,
    Integer promptTokens,
    Integer cachedTokens,
    Integer completionTokens,
    Double promptPerSecond,
    Double predictedPerSecond,
    Double promptMs,
    Double predictedMs,
    int chunkCount,
    boolean errored) {

  /** Cache-hit ratio computed from prompt usage. Null if either field is missing. */
  public Double cacheHitRatio() {
    if (promptTokens == null || cachedTokens == null || promptTokens == 0) {
      return null;
    }
    return (double) cachedTokens / (double) promptTokens;
  }

  /** Wall-clock generation duration in seconds, or null if first/finished not recorded. */
  public Double generationSeconds() {
    if (firstTokenAtMs < 0 || finishedAtMs <= firstTokenAtMs) {
      return null;
    }
    return (finishedAtMs - firstTokenAtMs) / 1000.0;
  }

  /** Wall-clock time-to-first-token in seconds (which approximates prompt-eval time). */
  public Double timeToFirstTokenSeconds() {
    if (firstTokenAtMs < 0 || startedAtMs <= 0) {
      return null;
    }
    return (firstTokenAtMs - startedAtMs) / 1000.0;
  }
}
