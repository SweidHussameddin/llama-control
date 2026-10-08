package de.sweid.llamactl.service;

import de.sweid.llamactl.model.LlamaFlags;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Translates a {@link LlamaFlags} value into the {@code argv} list passed to llama-server.
 * Centralised here so both the launcher (preview) and the process supervisor produce identical
 * command lines.
 */
@Component
public class FlagsRenderer {

  /**
   * Renders flags to CLI argv.
   *
   * @param flags the flag values
   * @return ordered argv list (without the binary path itself)
   */
  public List<String> render(LlamaFlags flags) {
    List<String> args = new ArrayList<>();

    addStr(args, "-m", flags.modelPath());
    addStr(args, "--alias", flags.alias());

    addStr(args, "--host", flags.host());
    addInt(args, "--port", flags.port());
    addStr(args, "--api-key", flags.apiKey());
    if (Boolean.TRUE.equals(flags.metrics())) {
      args.add("--metrics");
    }
    if (Boolean.TRUE.equals(flags.jinja())) {
      args.add("--jinja");
    }

    addInt(args, "-c", flags.ctxSize());
    addInt(args, "-ngl", flags.gpuLayers());
    addInt(args, "-t", flags.threads());
    addInt(args, "-b", flags.batchSize());
    addInt(args, "-ub", flags.ubatchSize());
    addInt(args, "--parallel", flags.parallel());
    addInt(args, "-s", flags.seed());
    addInt(args, "--keep", flags.keep());

    if (Boolean.TRUE.equals(flags.cpuMoe())) {
      args.add("--cpu-moe");
    } else {
      addInt(args, "--n-cpu-moe", flags.cpuMoeLayers());
    }

    if (Boolean.TRUE.equals(flags.kvUnified())) {
      args.add("--kv-unified");
    }
    if (Boolean.TRUE.equals(flags.noKvOffload())) {
      args.add("-nkvo");
    }
    addStr(args, "-ctk", flags.cacheTypeK());
    addStr(args, "-ctv", flags.cacheTypeV());
    if (flags.flashAttn() != null) {
      args.add("-fa");
      args.add(flags.flashAttn() ? "on" : "off");
    }

    if (Boolean.TRUE.equals(flags.mlock())) {
      args.add("--mlock");
    }
    if (Boolean.TRUE.equals(flags.noMmap())) {
      args.add("--no-mmap");
    }

    addInt(args, "--cache-reuse", flags.cacheReuse());

    addDouble(args, "--rope-freq-base", flags.ropeFreqBase());
    addDouble(args, "--rope-freq-scale", flags.ropeFreqScale());
    addStr(args, "--rope-scaling", flags.ropeScaling());

    if (flags.extraArgs() != null) {
      args.addAll(flags.extraArgs());
    }

    return args;
  }

  private static void addStr(List<String> args, String flag, String value) {
    if (value != null && !value.isBlank()) {
      args.add(flag);
      args.add(value);
    }
  }

  private static void addInt(List<String> args, String flag, Integer value) {
    if (value != null) {
      args.add(flag);
      args.add(value.toString());
    }
  }

  private static void addDouble(List<String> args, String flag, Double value) {
    if (value != null) {
      args.add(flag);
      args.add(value.toString());
    }
  }
}
