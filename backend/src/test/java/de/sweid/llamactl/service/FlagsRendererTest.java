package de.sweid.llamactl.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.sweid.llamactl.model.LlamaFlags;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlagsRendererTest {

  private final FlagsRenderer renderer = new FlagsRenderer();

  private static LlamaFlags flags(
      String modelPath, Integer ctx, Boolean cpuMoe, Integer cpuMoeLayers, Boolean flashAttn,
      Integer cacheReuse, List<String> extra) {
    return new LlamaFlags(
        modelPath, "qwen", "127.0.0.1", 8080, null, true, true,
        ctx, 99, null, 2048, 512, 2, null, null,
        cpuMoeLayers, cpuMoe, null,
        null, null, "q8_0", "q8_0", flashAttn,
        null, null,
        cacheReuse,
        null, null, null,
        extra);
  }

  @Test
  void rendersOnlyFlagsThatAreSet() {
    List<String> args = renderer.render(flags("/m/a.gguf", 8192, null, null, true, 256, null));

    assertThat(args)
        .containsExactly(
            "-m", "/m/a.gguf", "--alias", "qwen", "--host", "127.0.0.1", "--port", "8080",
            "--metrics", "--jinja", "-c", "8192", "-ngl", "99", "-b", "2048", "-ub", "512",
            "--parallel", "2", "-ctk", "q8_0", "-ctv", "q8_0", "-fa", "on",
            "--cache-reuse", "256");
  }

  @Test
  void cpuMoeWinsOverLayerCountAndExtraArgsGoLast() {
    List<String> args =
        renderer.render(flags("/m/a.gguf", null, true, 12, false, null, List.of("--verbose")));

    assertThat(args).contains("--cpu-moe").doesNotContain("--n-cpu-moe");
    assertThat(args).containsSequence("-fa", "off");
    assertThat(args).endsWith("--verbose");
  }

  @Test
  void blankStringsAreSkipped() {
    LlamaFlags f = new LlamaFlags(
        "/m/a.gguf", "  ", null, null, "", null, null,
        null, null, null, null, null, null, null, null,
        null, null, null,
        null, null, null, null, null,
        null, null,
        null,
        null, null, null,
        null);

    assertThat(renderer.render(f)).containsExactly("-m", "/m/a.gguf");
  }
}
