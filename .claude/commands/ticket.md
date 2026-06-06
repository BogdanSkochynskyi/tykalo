---
description: Execute a Tykalo ticket from Tykalo_CC_Prompts.md
argument-hint: [TK-XXX]
---

Think hard about this ticket from the start. Reason carefully through dependencies, architectural fit, edge cases, and potential failures **before** writing any code. The planning step (4) is where this thinking pays off most.

**Pre-flagged complex tickets — propose ultrathink:**
If `$ARGUMENTS` matches one of the tickets below, pause right after locating the ticket (step 2) and explicitly ask:

> "This ticket is flagged as complex because of [brief reason]. Want me to ultrathink the plan? (yes / no / skip with think hard)"

Wait for the answer before continuing to the dependency check.

| Ticket | Why it's complex |
|---|---|
| TK-156 | Escalation cron — race conditions, multi-level state, per-Nudger ack tracking |
| TK-173 | Telegram rate-limit handler — queue dynamics, retry logic, 429 backoff |
| TK-201 | RRULE parser — RFC 5545 subset, many edge cases (BYDAY, COUNT, UNTIL combinations) |
| TK-202 | Routine list type — new lifecycle, interacts with task copy semantics |
| TK-205 | Routine escalation — session-level escalation instead of per-task, branches in escalation cron |
| TK-225 | Two-way Google Calendar sync — webhook handling, change detection |
| TK-226 | GCal conflict resolution — last-write-wins with audit, potential data-loss scenarios |
| TK-241 | FSM dialog framework — state machine design, Redis persistence, cancel/error transitions |
| TK-311 | NL parser — prompt engineering, schema validation, regex/LLM hybrid logic |
| TK-501 | Notion two-way sync — polling without webhooks, field mapping, conflict resolution |

For tickets **not** on this list, "think hard" (the default for this command) is sufficient — don't over-allocate on simple CRUD or boilerplate work.

Execute Tykalo ticket **$ARGUMENTS**.

## 1. Load context
If `CLAUDE.md` hasn't been loaded in this session, read it now. It contains the stack (Spring Boot 4 + Java 25 + Lombok), conventions, mandatory testing policy, and Definition of Done.

## 2. Find the ticket
Open `Tykalo_CC_Prompts.md` and locate the section that starts with `### $ARGUMENTS` (e.g., `### TK-101`). If the section is missing, stop and tell the user — likely a typo in the ID.

## 3. Verify dependencies
The ticket may list `Deps: TK-YYY, TK-ZZZ`. Quickly check `git log`, the codebase, or recent commits to confirm those look implemented. If a dependency is clearly missing, flag it before proceeding rather than building on a shaky foundation.

## 4. Plan first, then implement
Before writing any code, **think hard** about the approach:
1. Outline a 3–5 bullet implementation plan: files to create/modify, edge cases, risks, integration points with existing code.
2. Identify what could go wrong (failure modes, race conditions, edge cases in input).
3. Consider whether existing patterns in the codebase fit, or if you need a new abstraction.
4. Wait for confirmation before writing code. Don't skip this step even for "obvious" tickets — planning surfaces bugs that would otherwise show up in implementation.

## 5. Implement with tests
- Create a feature branch matching the **TK ticket ID** (not the Linear TYK number): pattern `bohdan/tk-{XXX}-{short-name}` where `XXX` is the TK number from `$ARGUMENTS` (e.g., `TK-101` → `bohdan/tk-101-bootstrap-spring-boot`). Lowercase, hyphens only, no underscores. Adjust the user prefix if the git config differs.
- **Move the Linear ticket to "In Progress":** extract `TYK-N` from the ticket header in `Tykalo_CC_Prompts.md` (e.g., `### TK-101 (TYK-5) — ...` → `TYK-5`). Use Linear MCP — fetch statuses for team `Tykalo` (`mcp__linear__list_issue_statuses`), then update the issue (`mcp__linear__save_issue`) with the "In Progress" state. If the call fails, report the error and ask me to update manually — don't skip silently.
- Implement strictly per the acceptance criteria in the ticket.
- **Write tests in the same commit per CLAUDE.md mandatory testing policy.** Not as a follow-up, not as a TODO. JUnit 5 + AssertJ + Mockito for unit, Testcontainers for integration. See CLAUDE.md → Testing for what to cover and what's exempt.
- If the ticket adds a Flyway migration, place it under `src/main/resources/db/migration/` with the next available `V{N}__purpose.sql` prefix.
- Use Lombok (`@RequiredArgsConstructor`, `@Slf4j`, `@Data(of="id")` for entities) and Java 25 idioms (records, pattern matching, virtual threads where appropriate) per CLAUDE.md.

## 6. Validate
- Run `./gradlew check` (build + test + lint). Fix any failures iteratively.
- For DB-touching code: verify migrations apply cleanly on a fresh database via Testcontainers.
- For Telegram handlers: minimum one happy-path and one error-condition test.
- If you used Lombok in a new way, run `./gradlew compileJava` to confirm annotation processing.

## 7. Wrap up
Provide:
- Ask to commit with message in format `[$ARGUMENTS] (TYK-N) short imperative description` — both IDs are required so Linear's git integration auto-detects the ticket as a backup. Extract `TYK-N` from the ticket header.
- For the PR description, include a separate line `Closes TYK-N` — this auto-moves the ticket to Done on merge if Linear's GitHub integration is configured.
- Brief summary of files changed, tests added, tradeoffs/decisions made.
- **Move the Linear ticket to "In Review"** via Linear MCP (`mcp__linear__save_issue` with the "In Review" state for team `Tykalo`). If I confirm the PR is merged (or there's no PR for solo direct-to-main work), move to "Done" instead.
- If conventions evolved or new patterns emerged during this ticket, propose updates to CLAUDE.md.

## Communication
All chat output, code comments, commit messages, and log messages — in English (per CLAUDE.md → Communication).
