package de.sweid.llamactl.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Bounded ring buffer of llama-server log lines plus a list of live subscribers. Each line is
 * assigned a monotonically increasing id so SSE clients can resume from where they left off via
 * {@code Last-Event-ID}.
 */
@Component
public class LogBuffer {

  private static final int CAPACITY = 5000;

  /**
   * A single log line with its monotonic id.
   *
   * @param id 1-based monotonic identifier (never reused across the JVM's lifetime)
   * @param text the raw log line text without trailing newline
   */
  public record Entry(long id, String text) {}

  private final Deque<Entry> buffer = new ArrayDeque<>(CAPACITY);
  private final List<Consumer<Entry>> subscribers = new ArrayList<>();
  private long nextId = 1L;

  /**
   * Appends a line to the buffer and broadcasts it to subscribers.
   *
   * @param line a single log line, without trailing newline
   */
  public synchronized void append(String line) {
    Entry e = new Entry(nextId++, line);
    if (buffer.size() == CAPACITY) {
      buffer.removeFirst();
    }
    buffer.addLast(e);
    for (Consumer<Entry> sub : new ArrayList<>(subscribers)) {
      try {
        sub.accept(e);
      } catch (RuntimeException ignored) {
        // subscriber will be reaped lazily when it next throws
      }
    }
  }

  /**
   * Returns a snapshot copy of buffered entries with id greater than {@code afterId}. Pass 0 to
   * get everything currently buffered.
   *
   * @param afterId only return entries with id &gt; this value
   * @return list of entries, oldest first
   */
  public synchronized List<Entry> snapshotSince(long afterId) {
    List<Entry> out = new ArrayList<>(buffer.size());
    for (Entry e : buffer) {
      if (e.id() > afterId) {
        out.add(e);
      }
    }
    return out;
  }

  /**
   * Returns text-only snapshot of buffered entries — convenience for the non-streaming {@code GET
   * /api/logs} endpoint.
   *
   * @return list of log lines, oldest first
   */
  public synchronized List<String> snapshot() {
    List<String> out = new ArrayList<>(buffer.size());
    for (Entry e : buffer) {
      out.add(e.text());
    }
    return out;
  }

  /** Clears the buffer; called when a new process is starting. */
  public synchronized void clear() {
    buffer.clear();
  }

  /**
   * Registers a live subscriber that receives every new appended entry.
   *
   * @param sink consumer that receives subsequent entries
   * @return a handle that, when called, unregisters the subscriber
   */
  public synchronized Runnable subscribe(Consumer<Entry> sink) {
    subscribers.add(sink);
    return () -> {
      synchronized (LogBuffer.this) {
        subscribers.remove(sink);
      }
    };
  }
}
