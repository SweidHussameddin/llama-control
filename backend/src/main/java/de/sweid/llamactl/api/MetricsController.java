package de.sweid.llamactl.api;

import de.sweid.llamactl.model.MetricsSnapshot;
import de.sweid.llamactl.service.MetricsPollerService;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live metrics — snapshot for first paint, SSE for live updates. */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

  private final MetricsPollerService poller;

  /**
   * Wires the poller.
   *
   * @param poller scheduled metrics poller
   */
  public MetricsController(MetricsPollerService poller) {
    this.poller = poller;
  }

  /**
   * Returns the latest polled snapshot.
   *
   * @return snapshot
   */
  @GetMapping
  public MetricsSnapshot latest() {
    return poller.latest();
  }

  /**
   * SSE stream of metric snapshots — one event per polling tick.
   *
   * @return Server-Sent Events emitter
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L);

    try {
      emitter.send(SseEmitter.event().data(poller.latest()));
    } catch (IOException e) {
      emitter.completeWithError(e);
      return emitter;
    }

    Runnable unsubscribe =
        poller.subscribe(
            snap -> {
              try {
                emitter.send(SseEmitter.event().data(snap));
              } catch (IOException e) {
                emitter.completeWithError(e);
                throw new RuntimeException(e);
              }
            });

    emitter.onCompletion(unsubscribe);
    emitter.onTimeout(unsubscribe);
    emitter.onError(t -> unsubscribe.run());
    return emitter;
  }
}
