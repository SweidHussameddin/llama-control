package de.sweid.llamactl.model;

import java.util.List;

/**
 * Full set of llama-server flags exposed by the launcher form. Every field maps 1:1 to a
 * llama-server CLI flag; {@code null} means "don't pass it" (use llama-server's default).
 *
 * <p>Covers everything LM Studio's load dialog exposes plus the high-value flags it doesn't:
 * {@code --cache-reuse}, {@code -ub}, {@code --jinja}, {@code --alias}, {@code --metrics},
 * {@code --keep}, {@code --api-key}, plus a free-form {@code extraArgs} escape hatch.
 */
public record LlamaFlags(
    // --- Model ---
    String modelPath,
    String alias,

    // --- Server ---
    String host,
    Integer port,
    String apiKey,
    Boolean metrics,
    Boolean jinja,

    // --- Compute / context ---
    Integer ctxSize,
    Integer gpuLayers,
    Integer threads,
    Integer batchSize,
    Integer ubatchSize,
    Integer parallel,
    Integer seed,
    Integer keep,

    // --- MoE ---
    Integer cpuMoeLayers,
    Boolean cpuMoe,
    Integer expertsUsed,

    // --- KV cache ---
    Boolean kvUnified,
    Boolean noKvOffload,
    String cacheTypeK,
    String cacheTypeV,
    Boolean flashAttn,

    // --- Memory ---
    Boolean mlock,
    Boolean noMmap,

    // --- Caching (the missing-from-LM-Studio star of the show) ---
    Integer cacheReuse,

    // --- RoPE ---
    Double ropeFreqBase,
    Double ropeFreqScale,
    String ropeScaling,

    // --- Escape hatch for anything we missed ---
    List<String> extraArgs) {}
