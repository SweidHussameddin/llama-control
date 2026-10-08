package de.sweid.llamactl.service;

import de.sweid.llamactl.model.MetricsSnapshot.CpuInfo;
import de.sweid.llamactl.model.MetricsSnapshot.RamInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads {@code /proc/stat}, {@code /proc/meminfo}, and {@code /proc/loadavg} to produce CPU and
 * RAM snapshots. Linux-only; on other platforms {@link #cpu()} and {@link #ram()} return null.
 */
@Service
public class SystemMonitorService {

  private static final Logger LOG = LoggerFactory.getLogger(SystemMonitorService.class);
  private static final Path PROC_STAT = Path.of("/proc/stat");
  private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");
  private static final Path PROC_LOADAVG = Path.of("/proc/loadavg");

  private final int coreCount = Runtime.getRuntime().availableProcessors();

  private long lastTotal;
  private long lastIdle;
  private volatile boolean procMissing;

  /**
   * Returns the current CPU snapshot, or null if {@code /proc/stat} isn't readable.
   *
   * @return CPU info
   */
  public CpuInfo cpu() {
    if (procMissing) {
      return null;
    }
    long[] sample = readCpu();
    if (sample == null) {
      return null;
    }
    Integer util = null;
    if (lastTotal > 0) {
      long deltaTotal = sample[0] - lastTotal;
      long deltaIdle = sample[1] - lastIdle;
      if (deltaTotal > 0) {
        double busyRatio = 1.0 - ((double) deltaIdle / (double) deltaTotal);
        util = (int) Math.round(Math.max(0.0, Math.min(1.0, busyRatio)) * 100.0);
      }
    }
    lastTotal = sample[0];
    lastIdle = sample[1];
    return new CpuInfo(util, coreCount, readLoadAvg1m());
  }

  /**
   * Returns the current RAM snapshot, or null if {@code /proc/meminfo} isn't readable.
   *
   * @return RAM info
   */
  public RamInfo ram() {
    if (procMissing) {
      return null;
    }
    try {
      List<String> lines = Files.readAllLines(PROC_MEMINFO, StandardCharsets.UTF_8);
      Long total = null;
      Long avail = null;
      for (String line : lines) {
        if (line.startsWith("MemTotal:")) {
          total = parseKb(line);
        } else if (line.startsWith("MemAvailable:")) {
          avail = parseKb(line);
        }
        if (total != null && avail != null) {
          break;
        }
      }
      if (total == null || avail == null) {
        return null;
      }
      long usedMb = (total - avail) / 1024L;
      long totalMb = total / 1024L;
      return new RamInfo(usedMb, totalMb);
    } catch (IOException e) {
      LOG.warn("/proc/meminfo not readable; disabling host monitoring: {}", e.toString());
      procMissing = true;
      return null;
    }
  }

  private long[] readCpu() {
    try {
      String line = Files.readString(PROC_STAT, StandardCharsets.UTF_8).split("\n", 2)[0];
      // "cpu  user nice system idle iowait irq softirq steal guest guest_nice"
      String[] parts = line.trim().split("\\s+");
      if (parts.length < 5) {
        return null;
      }
      long total = 0L;
      for (int i = 1; i < parts.length; i++) {
        total += Long.parseLong(parts[i]);
      }
      long idle = Long.parseLong(parts[4]);
      if (parts.length > 5) {
        idle += Long.parseLong(parts[5]); // include iowait
      }
      return new long[] {total, idle};
    } catch (IOException e) {
      LOG.warn("/proc/stat not readable; disabling host monitoring: {}", e.toString());
      procMissing = true;
      return null;
    }
  }

  private Double readLoadAvg1m() {
    try {
      String line = Files.readString(PROC_LOADAVG, StandardCharsets.UTF_8).trim();
      String[] parts = line.split("\\s+");
      if (parts.length == 0) {
        return null;
      }
      return Double.parseDouble(parts[0]);
    } catch (IOException | NumberFormatException e) {
      return null;
    }
  }

  private static Long parseKb(String line) {
    String[] parts = line.split("\\s+");
    if (parts.length < 2) {
      return null;
    }
    try {
      return Long.parseLong(parts[1]);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
