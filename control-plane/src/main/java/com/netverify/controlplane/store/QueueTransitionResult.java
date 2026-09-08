package com.netverify.controlplane.store;

/**
 * Outcome of an atomic lifecycle transition attempted through
 * {@link InMemoryTestStore}.
 *
 * <ul>
 *   <li>{@code SUCCESS} — the record was in an allowed source state and was updated
 *   <li>{@code NOT_FOUND} — no record with that id
 *   <li>{@code CONFLICT} — the record existed but was in a disallowed source state
 * </ul>
 */
public enum QueueTransitionResult {
    SUCCESS,
    NOT_FOUND,
    CONFLICT
}
