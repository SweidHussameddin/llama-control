package de.sweid.llamactl.model;

/**
 * Metadata for a discovered {@code .gguf} model file on disk. Kept intentionally thin — no GGUF
 * header parsing, no HuggingFace metadata. Just what the launcher needs to fill the {@code -m}
 * flag.
 *
 * @param name display name (parent dir + filename)
 * @param path absolute path to the .gguf file
 * @param sizeBytes file size in bytes
 */
public record ModelFile(String name, String path, long sizeBytes) {}
