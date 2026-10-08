package de.sweid.llamactl.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.sweid.llamactl.config.LlamaControlProperties;
import de.sweid.llamactl.model.RequestObservation;
import de.sweid.llamactl.service.GemmaStreamTranslator;
import de.sweid.llamactl.service.GemmaStreamTranslator.DeltaKind;
import de.sweid.llamactl.service.GemmaStreamTranslator.OutputDelta;
import de.sweid.llamactl.service.LlamaServerService;
import de.sweid.llamactl.service.RequestObserverService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transparent OpenAI-compatible reverse proxy in front of llama-server. Clients (OpenCode,
 * Continue, etc.) point at this server's {@code /v1/...} and we forward to llama-server's
 * upstream {@code /v1/...}. The whole point is that we sit on the request path, so we observe
 * streaming responses in real time — that's how the dashboard gets live tok/s, cache-hit ratio,
 * and prompt/completion sizes without trying to poll llama-server's HTTP API (which serialises
 * behind the inference loop).
 *
 * <p>Streaming SSE responses are read chunk-by-chunk and written through to the client without
 * buffering, so we add negligible latency. Non-streaming JSON responses are buffered (small),
 * observed, and forwarded.
 */
@RestController
@RequestMapping("/v1")
public class OpenAiProxyController {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiProxyController.class);

  /** Request headers we don't want to forward upstream (hop-by-hop, or set by us). */
  private static final Set<String> SKIP_REQUEST_HEADERS =
      Set.of(
          "host",
          "content-length",
          "connection",
          "transfer-encoding",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailers",
          "upgrade");

  /** Response headers Spring will set itself; skip to avoid duplicates. */
  private static final Set<String> SKIP_RESPONSE_HEADERS =
      Set.of(
          "content-length",
          "connection",
          "transfer-encoding",
          "keep-alive",
          "proxy-authenticate",
          "trailer",
          "trailers",
          "upgrade");

  private static final Set<String> COMPLETION_PATHS =
      Set.of("/v1/completions", "/v1/chat/completions");

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          // No request timeout — completion responses can be long. Client disconnect handles it.
          .build();

  private final ObjectMapper mapper;
  private final LlamaControlProperties props;
  private final LlamaServerService server;
  private final RequestObserverService observer;

  /**
   * Wires dependencies.
   *
   * @param mapper Spring-managed Jackson mapper
   * @param props bound config (for upstream host/port defaults)
   * @param server process supervisor (to know if upstream is alive + on which port)
   * @param observer where observations get recorded
   */
  public OpenAiProxyController(
      ObjectMapper mapper,
      LlamaControlProperties props,
      LlamaServerService server,
      RequestObserverService observer) {
    this.mapper = mapper;
    this.props = props;
    this.server = server;
    this.observer = observer;
  }

  /**
   * Catch-all forwarder. Handles GET (e.g. {@code /v1/models}), POST (completions, embeddings),
   * and streaming SSE responses.
   *
   * @param req the inbound request
   * @param resp the outbound response we forward to
   * @throws IOException on network failure either side
   */
  @RequestMapping("/**")
  public void proxy(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String path = req.getRequestURI();
    String query = req.getQueryString();
    String upstreamUrl =
        upstreamBase() + path + (query == null || query.isEmpty() ? "" : "?" + query);

    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(upstreamUrl));

    // Read the inbound body once. For completion endpoints we inject
    // `timings_per_token: true` so llama-server includes a fresh
    // `timings.predicted_per_second` value in every SSE chunk — that's what powers
    // the dashboard's accurate in-flight tok/s number. Chunk-counting alone was
    // unreliable because llama-server bundles multiple tokens per SSE chunk.
    byte[] requestBody = req.getInputStream().readAllBytes();
    boolean isCompletionPath = COMPLETION_PATHS.contains(path);
    if (isCompletionPath && requestBody.length > 0) {
      try {
        JsonNode tree = mapper.readTree(requestBody);
        if (tree.isObject() && !tree.has("timings_per_token")) {
          ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("timings_per_token", true);
          requestBody = mapper.writeValueAsBytes(tree);
        }
      } catch (IOException ignored) {
        // body wasn't JSON we could parse — leave it alone
      }
    }
    String method = req.getMethod();
    if (requestBody.length > 0) {
      b.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }

    // Forward inbound headers
    Enumeration<String> names = req.getHeaderNames();
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
        continue;
      }
      b.header(name, req.getHeader(name));
    }

    HttpResponse<InputStream> upstream;
    try {
      upstream = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (java.net.ConnectException ce) {
      resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
      resp.setContentType("application/json");
      resp.getOutputStream()
          .write(
              ("{\"error\":\"llama-server not reachable at "
                      + upstreamBase()
                      + "\"}")
                  .getBytes(StandardCharsets.UTF_8));
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return;
    }

    // Mirror status + headers
    resp.setStatus(upstream.statusCode());
    upstream
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (SKIP_RESPONSE_HEADERS.contains(name.toLowerCase())) {
                return;
              }
              for (String v : values) {
                resp.addHeader(name, v);
              }
            });

    String contentType = upstream.headers().firstValue("content-type").orElse("");
    boolean isSse = contentType.startsWith("text/event-stream");
    boolean isCompletion = COMPLETION_PATHS.contains(path);

    if (isSse && isCompletion) {
      observeAndStream(path, upstream.body(), resp);
    } else if (isCompletion && contentType.startsWith("application/json")) {
      observeAndForwardJson(path, upstream.body(), resp);
    } else {
      try (InputStream in = upstream.body();
          OutputStream out = resp.getOutputStream()) {
        in.transferTo(out);
      }
    }
  }

  /**
   * Streams an SSE body through to the client and parses each {@code data:} chunk for observation.
   * Writes happen line-by-line and flush immediately so we don't add buffering latency.
   */
  private void observeAndStream(String path, InputStream upstream, HttpServletResponse resp)
      throws IOException {
    long started = System.currentTimeMillis();
    long firstToken = -1L;
    int chunkCount = 0;
    Integer promptTokens = null;
    Integer cachedTokens = null;
    Integer completionTokens = null;
    Double ppPerSecond = null;
    Double tgPerSecond = null;
    Double promptMs = null;
    Double predictedMs = null;
    boolean errored = false;

    long streamId = observer.startRequest();
    int skippedChunkSamples = 0;
    // Per-stream Gemma 4 translator. Cheap no-op for non-Gemma streams (just looks for marker
    // substrings that won't be there). For Gemma 4 it splits delta.content into reasoning_content
    // and tool_calls before we emit downstream, so OpenCode actually recognises them.
    GemmaStreamTranslator translator = new GemmaStreamTranslator(mapper);
    JsonNode lastChunkTemplate = null;
    try (BufferedReader reader =
            new BufferedReader(new InputStreamReader(upstream, StandardCharsets.UTF_8));
        OutputStream out = resp.getOutputStream()) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.startsWith("data:")) {
          // Headers, comments, blank-line SSE separators — forward verbatim.
          out.write(line.getBytes(StandardCharsets.UTF_8));
          out.write('\n');
          if (line.isEmpty()) {
            out.flush();
          }
          continue;
        }

        String json = line.substring(5).trim();
        if ("[DONE]".equals(json) || json.isEmpty()) {
          // Flush whatever the translator buffered first
          List<OutputDelta> tail = translator.flush();
          if (!tail.isEmpty() && lastChunkTemplate != null) {
            emitTranslatedChunks(out, lastChunkTemplate, tail);
          }
          out.write(line.getBytes(StandardCharsets.UTF_8));
          out.write('\n');
          continue;
        }
        JsonNode parsed = null;
        try {
          JsonNode node = mapper.readTree(json);
          parsed = node;
          lastChunkTemplate = node;
          JsonNode choices = node.get("choices");
          if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode first = choices.get(0);
            JsonNode delta = first.get("delta");
            JsonNode text = first.get("text");
            // A chunk represents a generated token if it carries any of:
            //   - delta.content (regular chat content)
            //   - delta.reasoning_content (Qwen3 / DeepSeek thinking models)
            //   - delta.tool_calls[].function.{name,arguments} (function-calling streams —
            //     arguments JSON arrives token-by-token, content stays empty the whole time)
            //   - text (legacy /v1/completions response field)
            // Missing any of these used to make our tok/s look 5–10× lower than reality.
            boolean hasContent =
                delta != null
                    && delta.has("content")
                    && !delta.get("content").asText("").isEmpty();
            boolean hasReasoning =
                delta != null
                    && delta.has("reasoning_content")
                    && !delta.get("reasoning_content").asText("").isEmpty();
            boolean hasToolCall = false;
            if (delta != null && delta.has("tool_calls") && delta.get("tool_calls").isArray()) {
              for (JsonNode tc : delta.get("tool_calls")) {
                JsonNode fn = tc.get("function");
                if (fn != null
                    && ((fn.has("arguments") && !fn.get("arguments").asText("").isEmpty())
                        || (fn.has("name") && !fn.get("name").asText("").isEmpty()))) {
                  hasToolCall = true;
                  break;
                }
                if (tc.has("id") && !tc.get("id").asText("").isEmpty()) {
                  // first chunk of a new tool call: only `id`+`type` present, no args yet.
                  // counts as a generation step.
                  hasToolCall = true;
                  break;
                }
              }
            }
            boolean hasText = text != null && !text.asText("").isEmpty();
            boolean produced = hasContent || hasReasoning || hasToolCall || hasText;
            if (!produced && skippedChunkSamples < 5 && choices != null && choices.size() > 0) {
              // Diagnostic: log first few uncounted chunks per stream so we can identify
              // any delta-field shapes we're not aware of. If your in-flight tok/s looks
              // suspiciously low and these lines flood the log, this is the field we
              // need to add to the counter.
              LOG.info("uncounted chunk: {}", json);
              skippedChunkSamples++;
            }
            if (produced) {
              if (firstToken < 0) {
                firstToken = System.currentTimeMillis();
              }
              chunkCount++;
              // Notify the observer per content chunk so the dashboard's in-flight
              // tok/s gauge can update DURING the response, not just after it ends.
              observer.observeChunk(streamId);
            }
          }
          JsonNode timings = node.get("timings");
          if (timings != null) {
            ppPerSecond = optDouble(timings, "prompt_per_second", ppPerSecond);
            tgPerSecond = optDouble(timings, "predicted_per_second", tgPerSecond);
            promptMs = optDouble(timings, "prompt_ms", promptMs);
            predictedMs = optDouble(timings, "predicted_ms", predictedMs);
            // Live rate update for the in-flight dashboard tile — llama-server's own
            // computed number, exact and current.
            Double currentRate = optDouble(timings, "predicted_per_second", null);
            if (currentRate != null) {
              observer.observeReportedRate(streamId, currentRate);
            }
          }
          // Capture token counts: prefer `usage` (OpenAI-standard), then fall back to
          // `timings.prompt_n`/`predicted_n`/`cache_n` — those are always present in
          // llama-server's final chunk, even when the client didn't ask for include_usage.
          JsonNode usage = node.get("usage");
          if (usage != null) {
            promptTokens = optInt(usage, "prompt_tokens", promptTokens);
            completionTokens = optInt(usage, "completion_tokens", completionTokens);
            JsonNode details = usage.get("prompt_tokens_details");
            if (details != null) {
              cachedTokens = optInt(details, "cached_tokens", cachedTokens);
            }
          }
          if (timings != null) {
            if (promptTokens == null) {
              promptTokens = optInt(timings, "prompt_n", null);
            }
            if (completionTokens == null) {
              completionTokens = optInt(timings, "predicted_n", null);
            }
            if (cachedTokens == null) {
              cachedTokens = optInt(timings, "cache_n", null);
            }
          }
        } catch (RuntimeException re) {
          LOG.debug("Could not parse SSE chunk for observation: {}", re.toString());
        }

        // Translate vs forward decision. Only chunks with a real content text get fed to the
        // Gemma translator; everything else (structured tool_calls already, role-only, etc.)
        // is passed through verbatim.
        JsonNode contentNode =
            (parsed == null)
                ? null
                : parsed.path("choices").path(0).path("delta").get("content");
        boolean hasContentText =
            contentNode != null && contentNode.isTextual() && !contentNode.asText().isEmpty();
        if (hasContentText) {
          List<OutputDelta> translated = translator.ingest(contentNode.asText());
          emitTranslatedChunks(out, parsed, translated);
        } else {
          out.write(line.getBytes(StandardCharsets.UTF_8));
          out.write('\n');
        }
      }
    } catch (IOException e) {
      errored = true;
      LOG.debug("SSE proxy stream ended with error: {}", e.toString());
      throw e;
    } finally {
      // Summary log so it's easy to compare our chunk count vs llama-server's reported
      // predicted_n (= the true generated-token count). If chunkCount lags predicted_n
      // significantly, we're missing some chunk shape. They should match within ±1.
      LOG.info(
          "stream summary: chunks={} predicted_n={} prompt_n={} cache_n={} "
              + "predicted_per_second={} skipped_uncounted_samples_seen={}",
          chunkCount,
          completionTokens,
          promptTokens,
          cachedTokens,
          tgPerSecond,
          skippedChunkSamples);
      observer.endRequest(
          streamId,
          new RequestObservation(
              path,
              started,
              firstToken,
              System.currentTimeMillis(),
              promptTokens,
              cachedTokens,
              completionTokens,
              ppPerSecond,
              tgPerSecond,
              promptMs,
              predictedMs,
              chunkCount,
              errored));
    }
  }

  /** Forwards a non-streaming JSON response, observing the {@code usage}/{@code timings} blocks. */
  private void observeAndForwardJson(String path, InputStream upstream, HttpServletResponse resp)
      throws IOException {
    final long started = System.currentTimeMillis();
    byte[] body;
    try (InputStream in = upstream) {
      body = in.readAllBytes();
    }
    resp.getOutputStream().write(body);

    Integer promptTokens = null;
    Integer cachedTokens = null;
    Integer completionTokens = null;
    Double ppPerSecond = null;
    Double tgPerSecond = null;
    Double promptMs = null;
    Double predictedMs = null;
    try {
      JsonNode node = mapper.readTree(body);
      JsonNode usage = node.get("usage");
      if (usage != null) {
        promptTokens = optInt(usage, "prompt_tokens", null);
        completionTokens = optInt(usage, "completion_tokens", null);
        JsonNode details = usage.get("prompt_tokens_details");
        if (details != null) {
          cachedTokens = optInt(details, "cached_tokens", null);
        }
      }
      JsonNode timings = node.get("timings");
      if (timings != null) {
        ppPerSecond = optDouble(timings, "prompt_per_second", null);
        tgPerSecond = optDouble(timings, "predicted_per_second", null);
        promptMs = optDouble(timings, "prompt_ms", null);
        predictedMs = optDouble(timings, "predicted_ms", null);
      }
    } catch (IOException ignored) {
      // not JSON or no usage — don't observe
    }

    long syncId = observer.startRequest();
    observer.endRequest(
        syncId,
        new RequestObservation(
            path,
            started,
            started,
            System.currentTimeMillis(),
            promptTokens,
            cachedTokens,
            completionTokens,
            ppPerSecond,
            tgPerSecond,
            promptMs,
            predictedMs,
            1,
            false));
  }

  private String upstreamBase() {
    return server.upstreamBaseUrl();
  }

  /**
   * Writes one downstream SSE chunk per translated delta. Each chunk reuses the upstream chunk's
   * metadata (id, model, created, etc.) with the delta replaced by the appropriate translated
   * field (content / reasoning_content / tool_calls).
   *
   * @param out downstream output stream
   * @param template upstream chunk JSON to clone metadata from
   * @param deltas translated deltas to emit
   * @throws IOException on output write failure
   */
  private void emitTranslatedChunks(OutputStream out, JsonNode template, List<OutputDelta> deltas)
      throws IOException {
    for (OutputDelta od : deltas) {
      ObjectNode chunk = (ObjectNode) template.deepCopy();
      JsonNode choicesNode = chunk.get("choices");
      if (!(choicesNode instanceof ArrayNode) || choicesNode.size() == 0) {
        continue;
      }
      ObjectNode firstChoice = (ObjectNode) choicesNode.get(0);
      ObjectNode newDelta = buildTranslatedDelta(od);
      firstChoice.set("delta", newDelta);
      out.write(("data: " + mapper.writeValueAsString(chunk)).getBytes(StandardCharsets.UTF_8));
      out.write('\n');
      out.write('\n');
    }
    out.flush();
  }

  private ObjectNode buildTranslatedDelta(OutputDelta od) {
    ObjectNode newDelta = mapper.createObjectNode();
    if (od.kind() == DeltaKind.CONTENT) {
      newDelta.put("content", od.text());
    } else if (od.kind() == DeltaKind.REASONING_CONTENT) {
      newDelta.put("reasoning_content", od.text());
    } else {
      try {
        ArrayNode toolCalls = mapper.createArrayNode();
        toolCalls.add(mapper.readTree(od.text()));
        newDelta.set("tool_calls", toolCalls);
      } catch (IOException e) {
        // Couldn't reparse the tool-call JSON we just produced — fall back to raw content
        // so something reaches the client.
        newDelta.put("content", od.text());
      }
    }
    return newDelta;
  }

  private static Integer optInt(JsonNode n, String field, Integer fallback) {
    JsonNode v = n.get(field);
    return (v == null || v.isNull()) ? fallback : v.asInt();
  }

  private static Double optDouble(JsonNode n, String field, Double fallback) {
    JsonNode v = n.get(field);
    return (v == null || v.isNull()) ? fallback : v.asDouble();
  }
}
