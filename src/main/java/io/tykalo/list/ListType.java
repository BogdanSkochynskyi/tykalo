package io.tykalo.list;

import lombok.Getter;

/**
 * The kind of a {@link TaskList}. The type drives default behaviour; each value carries the
 * {@link NudgerDefaultPolicy} it should be created with, making the type the single source of
 * truth for per-type Nudger defaults.
 */
@Getter
public enum ListType {

    /** Simple list, no time, Nudgers off. */
    CHECKLIST(NudgerDefaultPolicy.OFF),

    /** Recurring as a whole, Nudgers opt-in. */
    ROUTINE(NudgerDefaultPolicy.OPT_IN),

    /** Full tasks with deadlines, Nudgers decided per task. */
    PROJECT(NudgerDefaultPolicy.ON_PER_TASK),

    /** Quick-capture default list, Nudgers off. */
    INBOX(NudgerDefaultPolicy.OFF);

    private final NudgerDefaultPolicy defaultNudgerPolicy;

    ListType(final NudgerDefaultPolicy defaultNudgerPolicy) {
        this.defaultNudgerPolicy = defaultNudgerPolicy;
    }
}
