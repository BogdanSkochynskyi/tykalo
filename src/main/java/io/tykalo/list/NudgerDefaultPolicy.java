package io.tykalo.list;

/**
 * Default escalation behaviour applied to tasks in a {@link TaskList}.
 *
 * <ul>
 *   <li>{@code OFF} — Nudgers never fire (CHECKLIST, INBOX).</li>
 *   <li>{@code OPT_IN} — Nudgers fire only when the list is explicitly opted in (ROUTINE).</li>
 *   <li>{@code ON_PER_TASK} — Nudgers are decided per task (PROJECT).</li>
 * </ul>
 */
public enum NudgerDefaultPolicy {
    OFF,
    OPT_IN,
    ON_PER_TASK
}
