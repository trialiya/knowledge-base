package io.github.trialiya.kb.model.phrase.dto;

/**
 * Reorder within the phrase's own category. {@code position} is the target slot — a real sibling
 * position value (gaps tolerated), not an ordinal index. Same windowed-shift convention as document
 * move: the frontend passes the position of the neighbour at the drop location.
 */
public record MovePhraseRequest(int position) {}
