package de.sweid.llamactl.service;

import de.sweid.llamactl.model.MetricsSnapshot.GpuInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Wraps {@code nvidia-smi --query-gpu} to produce a {@link GpuInfo} snapshot. */
@Service
public class GpuMonitorService {

  private static final Logger LOG = LoggerFactory.getLogger(GpuMonitorService.class);
  private static final String[] QUERY = {
    "nvidia-smi",
    "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw",
    "--format=csv,noheader,nounits"
  };

  private volatile boolean nvidiaMissing;

  /**
   * Returns a snapshot of GPU 0 or null if no NVIDIA driver is installed.
   *
   * @return GPU info or null
   */
  public GpuInfo snapshot() {
    if (nvidiaMissing) {
      return null;
    }
    try {
      Process p = new ProcessBuilder(QUERY).redirectErrorStream(true).start();
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        String line = r.readLine();
        p.waitFor();
        if (line == null || line.isBlank()) {
          return null;
        }
        return parse(line);
      }
    } catch (java.io.IOException e) {
      LOG.warn("nvidia-smi not available; disabling GPU metrics: {}", e.getMessage());
      nvidiaMissing = true;
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private GpuInfo parse(String csv) {
    String[] parts = csv.split(",");
    if (parts.length < 6) {
      return null;
    }
    return new GpuInfo(
        parts[0].trim(),
        parseInt(parts[1]),
        parseInt(parts[2]),
        parseInt(parts[3]),
        parseInt(parts[4]),
        parseDouble(parts[5]));
  }

  private static Integer parseInt(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Double parseDouble(String s) {
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
