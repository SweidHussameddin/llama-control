package de.sweid.llamactl.model;

import java.util.List;

/**
 * Snapshot of the supervised llama-server process state.
 *
 * @param running whether a process is currently alive
 * @param pid process id if running, else -1
 * @param profileId id of the profile used to start the process, if any
 * @param startedFlags the {@link LlamaFlags} the running process was launched with — null when
 *     idle. The frontend uses this to pre-fill the launcher form so what's shown matches what's
 *     running
 * @param command rendered argv (for display in the UI)
 * @param startedAtEpochMs when the process started, or null
 * @param exitCode last exit code if the process has terminated, else null
 * @param baseUrl OpenAI-compatible base URL pointing at the running server
 */
public record ServerStatus(
    boolean running,
    long pid,
    String profileId,
    LlamaFlags startedFlags,
    List<String> command,
    Long startedAtEpochMs,
    Integer exitCode,
    String baseUrl) {}
