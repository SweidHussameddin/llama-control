package de.sweid.llamactl.api;

import de.sweid.llamactl.service.LogBuffer;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Exposes the rolling log buffer as a snapshot and as a Server-Sent Events stream. */
@RestController
@RequestMapping("/api/logs")
public class LogsController {

  private static final long SSE_TIMEOUT_MS = 0L;

  private final LogBuffer logs;

  /**
   * Wires the log buffer.
   *
   * @param logs shared log buffer
   */
  public LogsController(LogBuffer logs) {
    this.logs = logs;
  }

  /**
   * Returns the full current ring buffer (most recent N lines).
   *
   * @return list of log lines, oldest first
   */
  @GetMapping
  public List<String> snapshot() {
    return logs.snapshot();
  }

  /**
   * SSE stream — only sends entries newer than the client's {@code Last-Event-ID}. This means
   * reconnects (idle proxy timeouts, transient network blips) don't re-deliver the entire backlog,
   * and switching browser tabs and back in. On a fresh connection (no header), the entire backlog
   * is delivered as the initial "catch up" payload.
   *
   * @param lastEventIdHeader the {@code Last-Event-ID} header sent automatically by browsers on
   *     reconnect; null on a fresh connection
   * @return Server-Sent Events emitter
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader) {
    long lastEventId = parseId(lastEventIdHeader);
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

    for (LogBuffer.Entry e : logs.snapshotSince(lastEventId)) {
      try {
        emitter.send(SseEmitter.event().id(Long.toString(e.id())).data(e.text()));
      } catch (IOException ex) {
        emitter.completeWithError(ex);
        return emitter;
      }
    }

    Runnable unsubscribe =
        logs.subscribe(
            entry -> {
              try {
                emitter.send(
                    SseEmitter.event().id(Long.toString(entry.id())).data(entry.text()));
              } catch (IOException ex) {
                emitter.completeWithError(ex);
                throw new RuntimeException(ex);
              }
            });

    emitter.onCompletion(unsubscribe);
    emitter.onTimeout(unsubscribe);
    emitter.onError(t -> unsubscribe.run());
    return emitter;
  }

  private static long parseId(String s) {
    if (s == null || s.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
