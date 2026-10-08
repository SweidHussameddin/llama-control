package de.sweid.llamactl.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateful translator that converts Gemma 4's custom output markers into standard OpenAI-style
 * streaming deltas. The Gemma 4 26B chat template emits non-standard markers for reasoning
 * channels and tool calls that llama-server's built-in parsers (Hermes / Llama / Functionary /
 * deepseek-reasoning) don't recognise — so the model's tool calls and thinking arrive at the
 * client as raw text in the regular content field. This translator splits that text back into
 * the correct OpenAI fields so OpenCode actually sees them as tool calls and reasoning content.
 *
 * <p>Stateful per-stream — feed each content fragment via {@link #ingest(String)} and emit the
 * returned deltas as separate SSE chunks downstream. Internal buffers hold partial markers that
 * span chunk boundaries.
 */
public class GemmaStreamTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(GemmaStreamTranslator.class);

  /** What field the emitted delta should map to in the downstream chunk. */
  public enum DeltaKind {
    CONTENT,
    REASONING_CONTENT,
    TOOL_CALL,
  }

  /**
   * One unit of translated output.
   *
   * @param kind which delta field this text belongs to
   * @param text plain text for CONTENT or REASONING_CONTENT; serialized OpenAI tool_call JSON
   *     object for TOOL_CALL
   */
  public record OutputDelta(DeltaKind kind, String text) {}

  private enum Mode {
    NORMAL,
    THOUGHT,
    TOOL_CALL_BODY,
  }

  private static final String CHANNEL_OPEN = "<|channel>";
  private static final String CHANNEL_CLOSE = "<channel|>";
  private static final String TOOL_OPEN = "<|tool_call>";
  private static final String TOOL_CLOSE = "<tool_call|>";
  /** Gemma uses {@code <|"|>...<|"|>} as string quotes inside tool-call argument blocks. */
  private static final String GEMMA_QUOTE = "<|\"|>";

  /** Matches a bare key name preceded by an opening brace or comma and followed by a colon. */
  private static final Pattern UNQUOTED_KEY =
      Pattern.compile("([{,]\\s*)([a-zA-Z_]\\w*)\\s*:");

  private final ObjectMapper mapper;
  private final StringBuilder buffer = new StringBuilder();
  private final StringBuilder toolBuffer = new StringBuilder();
  private Mode mode = Mode.NORMAL;
  private int toolCallIndex = 0;

  /**
   * Creates a translator using the given Jackson mapper for tool-call serialization.
   *
   * @param mapper Jackson mapper
   */
  public GemmaStreamTranslator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Feeds a content text fragment and returns the deltas to emit. An empty result is normal
   * when the fragment ends inside a marker — the buffer holds it for the next call.
   *
   * @param fragment raw text from an upstream {@code delta.content}
   * @return zero or more deltas to emit as separate downstream SSE chunks
   */
  public List<OutputDelta> ingest(String fragment) {
    if (fragment != null && !fragment.isEmpty()) {
      buffer.append(fragment);
    }
    return drain();
  }

  /**
   * Flushes anything left in the buffers at end-of-stream. Unterminated markers fall back to raw
   * content so the client at least sees the text.
   *
   * @return final deltas to emit
   */
  public List<OutputDelta> flush() {
    List<OutputDelta> out = new ArrayList<>();
    // Drain whatever's safe to emit
    out.addAll(drain());
    // Emit any remaining buffered content as best-effort
    if (buffer.length() > 0) {
      DeltaKind kind = (mode == Mode.THOUGHT) ? DeltaKind.REASONING_CONTENT : DeltaKind.CONTENT;
      out.add(new OutputDelta(kind, buffer.toString()));
      buffer.setLength(0);
    }
    if (toolBuffer.length() > 0) {
      out.add(new OutputDelta(DeltaKind.CONTENT, TOOL_OPEN + toolBuffer));
      toolBuffer.setLength(0);
    }
    return out;
  }

  private List<OutputDelta> drain() {
    List<OutputDelta> out = new ArrayList<>();
    boolean progress = true;
    while (progress) {
      progress = false;

      if (mode == Mode.TOOL_CALL_BODY) {
        int endIdx = buffer.indexOf(TOOL_CLOSE);
        if (endIdx < 0) {
          toolBuffer.append(buffer);
          buffer.setLength(0);
          break;
        }
        toolBuffer.append(buffer, 0, endIdx);
        buffer.delete(0, endIdx + TOOL_CLOSE.length());

        String toolJson = parseToolCall(toolBuffer.toString());
        if (toolJson != null) {
          out.add(new OutputDelta(DeltaKind.TOOL_CALL, toolJson));
        } else {
          // Couldn't parse — emit raw as content fallback so user at least sees something
          out.add(new OutputDelta(DeltaKind.CONTENT, TOOL_OPEN + toolBuffer + TOOL_CLOSE));
        }
        toolBuffer.setLength(0);
        mode = Mode.NORMAL;
        progress = true;
        continue;
      }

      // NORMAL or THOUGHT: scan for next marker
      int chanIdx = buffer.indexOf(CHANNEL_OPEN);
      int toolIdx = buffer.indexOf(TOOL_OPEN);

      int firstMarker;
      String firstMarkerStr;
      if (chanIdx < 0 && toolIdx < 0) {
        firstMarker = -1;
        firstMarkerStr = null;
      } else if (chanIdx >= 0 && (toolIdx < 0 || chanIdx < toolIdx)) {
        firstMarker = chanIdx;
        firstMarkerStr = CHANNEL_OPEN;
      } else {
        firstMarker = toolIdx;
        firstMarkerStr = TOOL_OPEN;
      }

      if (firstMarker < 0) {
        int safe = safeEmitLength(buffer);
        if (safe > 0) {
          DeltaKind kind = (mode == Mode.THOUGHT) ? DeltaKind.REASONING_CONTENT : DeltaKind.CONTENT;
          out.add(new OutputDelta(kind, buffer.substring(0, safe)));
          buffer.delete(0, safe);
          progress = true;
        }
        break;
      }

      if (firstMarker > 0) {
        DeltaKind kind = (mode == Mode.THOUGHT) ? DeltaKind.REASONING_CONTENT : DeltaKind.CONTENT;
        out.add(new OutputDelta(kind, buffer.substring(0, firstMarker)));
        buffer.delete(0, firstMarker);
      }

      if (firstMarkerStr.equals(CHANNEL_OPEN)) {
        int closeIdx = buffer.indexOf(CHANNEL_CLOSE, CHANNEL_OPEN.length());
        if (closeIdx < 0) {
          break; // wait for closing marker
        }
        String channelName = buffer.substring(CHANNEL_OPEN.length(), closeIdx).trim();
        buffer.delete(0, closeIdx + CHANNEL_CLOSE.length());
        mode = "thought".equals(channelName) ? Mode.THOUGHT : Mode.NORMAL;
        progress = true;
      } else {
        buffer.delete(0, TOOL_OPEN.length());
        mode = Mode.TOOL_CALL_BODY;
        progress = true;
      }
    }
    return out;
  }

  /**
   * Returns how many leading chars of the buffer are safe to emit without potentially cutting
   * a marker mid-way. If the buffer ends in what could be the start of a marker, hold it back.
   */
  private static int safeEmitLength(StringBuilder buf) {
    int n = buf.length();
    if (n == 0) {
      return 0;
    }
    int hold = Math.min(n, 16);
    String[] markers = {CHANNEL_OPEN, TOOL_OPEN};
    int holdBack = 0;
    for (String m : markers) {
      int maxPrefix = Math.min(m.length() - 1, hold);
      for (int i = maxPrefix; i >= 1; i--) {
        if (regionMatches(buf, n - i, m, 0, i)) {
          holdBack = Math.max(holdBack, i);
          break;
        }
      }
    }
    return n - holdBack;
  }

  private static boolean regionMatches(
      StringBuilder buf, int bufOffset, String s, int strOffset, int len) {
    if (bufOffset < 0 || bufOffset + len > buf.length()) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      if (buf.charAt(bufOffset + i) != s.charAt(strOffset + i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Parses a Gemma tool-call body of the form {@code call:name{key:value,...}} into an OpenAI
   * {@code tool_call} object serialized as JSON. Returns null on parse failure.
   */
  private String parseToolCall(String body) {
    String s = body.trim();
    if (!s.startsWith("call:")) {
      return null;
    }
    s = s.substring("call:".length());
    int brace = s.indexOf('{');
    if (brace < 0) {
      return null;
    }
    String name = s.substring(0, brace).trim();
    String argsRaw = s.substring(brace);
    String argsJson = gemmaArgsToJson(argsRaw);
    if (argsJson == null) {
      return null;
    }
    try {
      ObjectNode tc = mapper.createObjectNode();
      tc.put("index", toolCallIndex++);
      tc.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
      tc.put("type", "function");
      ObjectNode fn = mapper.createObjectNode();
      fn.put("name", name);
      fn.put("arguments", argsJson);
      tc.set("function", fn);
      return mapper.writeValueAsString(tc);
    } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
      LOG.warn("Could not serialize translated tool call: {}", e.toString());
      return null;
    }
  }

  /**
   * Best-effort conversion of Gemma's args block to JSON: replace {@code <|"|>...<|"|>} string
   * markers with JSON double-quotes, and quote bare object keys.
   */
  private static String gemmaArgsToJson(String s) {
    String trimmed = s.trim();
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      return null;
    }
    String unquoted = trimmed.replace(GEMMA_QUOTE, "\"");
    Matcher m = UNQUOTED_KEY.matcher(unquoted);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(
          sb, Matcher.quoteReplacement(m.group(1) + "\"" + m.group(2) + "\":"));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
