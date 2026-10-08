package de.sweid.llamactl.model;

/**
 * Named, persisted set of llama-server flags.
 *
 * @param id stable kebab-case identifier; also the filename stem on disk
 * @param name human-readable display name
 * @param flags the actual flag values
 */
public record Profile(String id, String name, LlamaFlags flags) {}
