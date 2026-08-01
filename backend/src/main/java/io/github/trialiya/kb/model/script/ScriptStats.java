package io.github.trialiya.kb.model.script;

/**
 * What one script run actually consumed. Returned to the model (so it can see it is approaching a
 * budget) and surfaced in the tool-call plaque, which is the only way a user can tell how much of
 * the repository a script touched.
 *
 * @param filesRead distinct files read via {@code kb.read} / {@code kb.outline}
 * @param bytesRead total bytes those reads returned
 * @param calls {@code kb.*} calls that actually did work — a repeat with the same arguments as an
 *     earlier call in this run is answered from the run's cache and does not add to this count (see
 *     {@code ScriptSession#cached})
 * @param filesEdited files created or modified — zero for a read-only run
 * @param elapsedMs wall-clock duration of the run
 */
public record ScriptStats(
        int filesRead, long bytesRead, int calls, int filesEdited, long elapsedMs) {}
