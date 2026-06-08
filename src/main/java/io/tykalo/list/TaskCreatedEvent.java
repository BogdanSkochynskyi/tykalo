package io.tykalo.list;

/**
 * Published by {@link TaskService} right after a task is persisted, carrying the task and the type of
 * the list it landed in. It lets other domains react to task creation without {@code list} depending
 * on them — the nudger domain listens for it to seed a Project task's default escalation ladder
 * (TK-155). The {@code task} is already persisted (its id is set); {@code listType} rides along so a
 * listener needn't reload the list.
 */
public record TaskCreatedEvent(Task task, ListType listType) {
}
