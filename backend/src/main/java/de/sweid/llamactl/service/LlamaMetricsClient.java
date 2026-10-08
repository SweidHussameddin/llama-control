package de.sweid.llamactl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.sweid.llamactl.model.MetricsSnapshot.SlotInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Tiny HTTP client for the supervised llama-server's {@code /metrics} (Prometheus exposition) and
 * {@code /slots} endpoints. Returns parsed values; never throws on connection failure (returns
 * empty results so the dashboard can render "unreachable").
 */
@Service
public class LlamaMetricsClient {

  // llama-server shares its HTTP loop with inference work. Under heavy load, /slots and
  // /metrics can stall for many seconds while the GPU is busy. Give them generous timeouts
  // so the dashboard doesn't go blank during active generation.
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
  private final ObjectMapper mapper;

  /**
   * Wires the JSON mapper.
   *
   * @param mapper Spring-managed Jackson mapper
   */
  public LlamaMetricsClient(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Fetches Prometheus metrics and parses the value of every {@code llamacpp:*} sample.
   *
   * @param baseUrl base URL of the OpenAI-compatible llama-server, e.g. {@code
   *     http://127.0.0.1:8080/v1}
   * @return map of metric name → value (empty on connection failure)
   */
  public Map<String, Double> fetchMetrics(String baseUrl) {
    String root = stripV1(baseUrl);
    Map<String, Double> out = new HashMap<>();
    String body = getOrNull(root + "/metrics");
    if (body == null) {
      return out;
    }
    for (String line : body.split("\n")) {
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int sp = line.lastIndexOf(' ');
      if (sp < 0) {
        continue;
      }
      String name = line.substring(0, sp);
      int brace = name.indexOf('{');
      if (brace > 0) {
        name = name.substring(0, brace);
      }
      try {
        out.put(name.trim(), Double.parseDouble(line.substring(sp + 1).trim()));
      } catch (NumberFormatException ignored) {
        // skip
      }
    }
    return out;
  }

  /**
   * Fetches and parses the {@code /slots} endpoint.
   *
   * @param baseUrl base URL of the llama-server
   * @return list of slot snapshots (empty on connection failure)
   */
  public List<SlotInfo> fetchSlots(String baseUrl) {
    String root = stripV1(baseUrl);
    String body = getOrNull(root + "/slots");
    if (body == null) {
      return List.of();
    }
    try {
      JsonNode arr = mapper.readTree(body);
      List<SlotInfo> out = new ArrayList<>();
      for (JsonNode n : arr) {
        out.add(
            new SlotInfo(
                n.path("id").asInt(-1),
                n.path("is_processing").asBoolean(false),
                optLong(n, "n_past"),
                optLong(n, "n_ctx"),
                optLong(n, "prompt_n"),
                optLong(n, "predict_n")));
      }
      return out;
    } catch (java.io.IOException e) {
      return List.of();
    }
  }

  /**
   * Returns true if the llama-server HTTP API answered any request in the last attempt.
   *
   * @param baseUrl base URL of the llama-server
   * @return reachability flag
   */
  public boolean ping(String baseUrl) {
    String root = stripV1(baseUrl);
    return getOrNull(root + "/health") != null;
  }

  private String getOrNull(String url) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
      HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() / 100 != 2) {
        return null;
      }
      return resp.body();
    } catch (java.io.IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static Long optLong(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return (v == null || v.isNull()) ? null : v.asLong();
  }

  private static String stripV1(String baseUrl) {
    if (baseUrl == null) {
      return "http://127.0.0.1:8080";
    }
    return baseUrl.endsWith("/v1") ? baseUrl.substring(0, baseUrl.length() - 3) : baseUrl;
  }
}
