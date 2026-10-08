package de.sweid.llamactl.model;

/**
 * Parsed GGUF metadata for a model file — just the fields the launcher form needs to set
 * sensible slider maxes.
 *
 * @param path absolute path of the .gguf file
 * @param architecture {@code general.architecture} (e.g. {@code qwen3moe}, {@code llama})
 * @param contextLength model's trained max context length, or null if not present
 * @param blockCount number of transformer blocks (== layer count), or null
 * @param headCount attention head count, or null
 */
public record ModelDetails(
    String path,
    String architecture,
    Integer contextLength,
    Integer blockCount,
    Integer headCount,
    Integer expertCount,
    Integer expertUsedCount) {}
