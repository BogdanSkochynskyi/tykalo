# Tykalo — Claude Code Prompts

Ready-to-use prompts for all 95 tickets. When working on a ticket, find it (Ctrl+F by TK-XXX), copy the prompt, paste into Claude Code.

**Before starting:** make sure CC has read `CLAUDE.md` — it contains all context, stack, conventions. If CC hasn't read it, say "read CLAUDE.md first".

**Base prefix for prompts** (optional): "Working on Linear ticket TYK-N (TK-XXX). Read CLAUDE.md if not already loaded, then:"

---

## ⚠️ GLOBAL RULE: Tests are mandatory

**Every ticket containing business logic must include tests in the same commit/PR.** This doesn't need to be repeated in each prompt — it's a global rule fixed in `CLAUDE.md` → Testing — MANDATORY POLICY.

If CC doesn't write tests on its own — that's a bug. In that case, add at the end of the prompt: "Don't forget tests per CLAUDE.md testing policy."

**Exceptions** (tests may be minimal or absent): pure config classes, DevOps tickets (Dockerfile, CI YAML), trivial DTOs. Prompts below mention tests explicitly where they matter most; where they don't, still write tests per CLAUDE.md.

---

# Phase 1 — Core MVP

## Foundation

### TK-101 (TYK-5) — Bootstrap Spring Boot 4.x

**Epic:** Foundation · **Estimate:** 1pt · **No deps**

Create a Spring Boot 4.0.6+ project on Gradle 9.x with Java 25 LTS toolchain.

**Acceptance:**
- Package root `io.tykalo`, main class `TykaloApplication`
- `application.yml` + profiles `application-dev.yml`, `application-prod.yml`
- Starter dependencies: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `lombok`
- Lombok annotation processor in Gradle config
- `gradle/libs.versions.toml` — version catalog (see CLAUDE.md → Library version policy)
- `./gradlew build` and `./gradlew bootRun` work
- `GET /actuator/health` returns 200
- `.gitignore` and `README.md` with basic setup
- JSpecify dependency for null-safety (`@NullMarked` on package-info.java in `io.tykalo`)

---

### TK-102 (TYK-6) — Docker Compose: Postgres 16 + Redis

**Epic:** Foundation · **Estimate:** 1pt · **No deps**

`docker-compose.yml` for local development.

**Acceptance:**
- Postgres 16 (database `tykalo`, user/password via .env)
- Redis 7 (no password for local)
- Persistent volumes for both
- Healthchecks
- `docker-compose up -d` starts both, `docker-compose down` stops cleanly
- `.env.example` with template credentials

---

### TK-103 (TYK-7) — Flyway migrations + initial users schema

**Epic:** Foundation · **Estimate:** 1pt · **Deps:** TK-101, TK-102

Integrate Flyway with Spring Boot. Create the first migration.

**Acceptance:**
- `V1__users_table.sql`: id (UUID PK), tg_chat_id (BIGINT UNIQUE NOT NULL), tg_username (VARCHAR), timezone (VARCHAR), quiet_hours_start (TIME), quiet_hours_end (TIME), locale (VARCHAR), created_at (TIMESTAMP WITH TIME ZONE)
- Index on `tg_chat_id`
- Default quiet hours: 22:00–07:00
- Flyway config in `application.yml` — auto-run on startup
- On startup the table is created and `flywayInfo` shows success

---

### TK-104 (TYK-8) — Register bot with @BotFather + secret management

**Epic:** Foundation · **Estimate:** 1pt · **No deps**

Create a bot via @BotFather (instructions in README), set up secret management.

**Acceptance:**
- README has step-by-step @BotFather instructions (including /newbot, /setcommands with the command list preview)
- `.env.example` has `TELEGRAM_BOT_TOKEN=` placeholder
- Application reads via `@Value("${telegram.bot.token}")` from ENV
- `.env` in `.gitignore`
- If the token is empty, the app fails to start with a clear error message

---

### TK-105 (TYK-9) — TelegramBots starter + /start handler + User entity

**Epic:** Foundation · **Estimate:** 2pt · **Deps:** TK-103, TK-104

Integrate `org.telegram:telegrambots-springboot-longpolling-starter:10.0.0` + `telegrambots-client:10.0.0`. Implement basic handler.

**Acceptance:**
- Versions sourced from `gradle/libs.versions.toml` (TK-101)
- TelegramBot bean (long polling mode locally via starter)
- User JPA entity matching the schema from TK-103
- UserRepository (Spring Data JPA)
- UserService with `findOrCreate(Update)` — creates User on first contact
- `/start` handler: auto-detect TZ from Telegram user (language_code → ZoneId map; fallback `Europe/Kyiv`), creates User, replies with greeting
- Custom annotation `@TelegramCommand("/cmd")` for future handlers + dispatcher that finds methods by command

---

### TK-106 (TYK-10) — Logback structured JSON + Sentry (optional)

**Epic:** Foundation · **Estimate:** 1pt · **No deps**

Configure structured logging.

**Acceptance:**
- Logback config with JSON encoder (logstash-logback-encoder)
- Useful fields: timestamp, level, logger, message, mdc (including `userId`, `chatId`)
- Profile `dev`: human-readable console output
- Profile `prod`: JSON output
- Optional: Sentry integration (only if `SENTRY_DSN` is set in env)

---

### TK-107 (TYK-11) — GitHub Actions CI: build + test

**Epic:** Foundation · **Estimate:** 1pt · **Deps:** TK-101

Basic CI workflow.

**Acceptance:**
- `.github/workflows/ci.yml`
- Triggers: push to `main`, PRs
- Steps: setup JDK 25, cache Gradle, `./gradlew build test`
- Postgres + Redis services for integration tests
- Status badge in README

---

## Data Model & Lists

### TK-111 (TYK-12) — Migrations: lists, tasks, list_messages

**Epic:** Data Model & Lists · **Estimate:** 1pt · **Deps:** TK-103

Create V2/V3/V4 Flyway migrations.

**Acceptance:**
- `V2__lists_table.sql`: id, owner_id FK, name, type (VARCHAR enum: CHECKLIST/ROUTINE/PROJECT/INBOX), recurrence_rule (TEXT NULL), nudger_default_policy (VARCHAR), created_at, archived_at (NULL)
- `V3__tasks_table.sql`: id, list_id FK, owner_id FK, title NOT NULL, description, due_at TIMESTAMPTZ, priority (VARCHAR), status (VARCHAR), recurrence_rule, gcal_event_id, tags (TEXT[]), created_at, updated_at, archived_at
- `V4__list_messages_table.sql`: id, list_id FK, tg_chat_id BIGINT, tg_message_id BIGINT, last_rendered_at TIMESTAMPTZ
- Indexes: tasks(owner_id, due_at), tasks(list_id), list_messages(list_id, tg_chat_id)

---

### TK-112 (TYK-13) — List entity with type enum

**Epic:** Data Model & Lists · **Estimate:** 1pt · **Deps:** TK-111

JPA entity for List.

**Acceptance:**
- `List` entity (warning: NOT `java.util.List` — use a domain-specific package, or name it `TaskList` to avoid conflict, but Flyway tablename is `lists`)
- `ListType` enum: `CHECKLIST`, `ROUTINE`, `PROJECT`, `INBOX`
- `NudgerDefaultPolicy` enum: `OFF`, `OPT_IN`, `ON_PER_TASK`
- ListRepository (Spring Data JPA)
- Static factories `TaskList.checklist(owner, name)`, `TaskList.project(owner, name)` etc. — with defaults per type

---

### TK-113 (TYK-14) — Task entity with optional fields

**Epic:** Data Model & Lists · **Estimate:** 1pt · **Deps:** TK-111, TK-112

JPA entity for Task.

**Acceptance:**
- `Task` entity. All fields except `title` optional (`Optional<>` for getters, NULL in DB)
- `TaskStatus` enum: `TODO`, `DONE`, `CANCELLED`
- `Priority` enum: `LOW`, `MEDIUM`, `HIGH`, `URGENT`
- `tags` as `List<String>` via JPA `@ElementCollection` or `String[]` Postgres
- TaskRepository with methods: `findByOwnerIdAndStatus`, `findOverdue(Instant now)`, `findByListId`

---

### TK-114 (TYK-15) — Repository + Service layer (CRUD)

**Epic:** Data Model & Lists · **Estimate:** 1pt · **Deps:** TK-112, TK-113

Basic service layer.

**Acceptance:**
- ListService: createList, getById, deleteList (soft via archived_at), findAllByOwner
- TaskService: createTask, completeTask, snoozeTask, deleteTask, findToday, findOverdue
- Transactional boundaries (`@Transactional` on service methods)
- Domain validation: name not blank, don't allow DONE → DONE twice

---

### TK-115 (TYK-16) — Auto-create Inbox on registration

**Epic:** Data Model & Lists · **Estimate:** 1pt · **Deps:** TK-105, TK-114

When `UserService.findOrCreate` creates a new user, immediately create an `Inbox` List of type `INBOX`.

**Acceptance:**
- In the same transaction as User creation
- Inbox has name "Inbox", type INBOX, NudgerDefaultPolicy OFF
- Idempotent: repeated /start doesn't create a duplicate Inbox
- Test: creating a new User → Inbox exists; repeated → nothing new

---

## Checklist Mode

### TK-121 (TYK-17) — Commands /lists, /list create, /list delete

**Epic:** Checklist Mode · **Estimate:** 2pt · **Deps:** TK-105, TK-114

List management commands.

**Acceptance:**
- `/lists` — shows all active Lists for user (type, task count)
- `/list create <name> [type]` — creates new, type optional (default CHECKLIST)
- `/list delete <name>` — confirmation prompt → soft delete
- Errors: blank name, invalid type, list with that name exists
- Tests for handler logic

---

### TK-122 (TYK-18) — /use &lt;name&gt; — current list context (Redis)

**Epic:** Checklist Mode · **Estimate:** 1pt · **Deps:** TK-121

Per-user "current list" state in Redis.

**Acceptance:**
- `/use <list_name>` sets current list for user in Redis (key `user:{userId}:currentList`, TTL 24h)
- Next `/add` without explicit list goes to current
- If no current — fallback to Inbox
- `/use` without arguments — shows current context
- `CurrentContextService` with get/set/clear

---

### TK-123 (TYK-19) — Bulk-add: multi-line → N tasks

**Epic:** Checklist Mode · **Estimate:** 2pt · **Deps:** TK-122

Multi-line message → N tasks in current list (CHECKLIST and INBOX only).

**Acceptance:**
- If message doesn't start with `/` and has > 1 line — bulk-add mode
- Each line = separate Task with title
- If current list is PROJECT/ROUTINE — show hint, don't create
- Bot replies `Added N tasks to list "X"`
- Empty lines ignored

---

### TK-124 (TYK-20) — Editable list message: rendering + inline keyboard

**Epic:** Checklist Mode · **Estimate:** 2pt · **Deps:** TK-114

Live list as a single chat message, updated in place.

**Acceptance:**
- When creating a new CHECKLIST and adding tasks — bot sends one message with formatted list
- InlineKeyboard with `✅` button next to each task (callback_data: `task:done:{taskId}`)
- Stores tg_message_id in `list_messages`
- If list already has a message — updates it instead of creating new
- ListRenderer service: `String render(List<Task>)` returns Markdown text

---

### TK-125 (TYK-21) — Callback handler ✅/❌ + Edit Message API

**Epic:** Checklist Mode · **Estimate:** 2pt · **Deps:** TK-124

Handle inline button clicks.

**Acceptance:**
- Callback `task:done:{id}` → mark task DONE, re-render list (strikethrough line)
- Callback `task:undo:{id}` → revert to TODO
- Edit Message API instead of new message
- Telegram callback_query answered (short toast "Done!")
- Idempotency: repeated click doesn't change state twice

---

## Task Management (Project mode)

### TK-131 (TYK-22) — /add without date

**Epic:** Task Management · **Estimate:** 1pt · **Deps:** TK-114, TK-122

Simple add to current list.

**Acceptance:**
- `/add <title>` — creates Task with title, no due_at
- If current list — goes there; else — Inbox
- Bot replies with new task ID and list name
- Support long titles (up to 500 chars)

---

### TK-132 (TYK-23) — /add with deadline

**Epic:** Task Management · **Estimate:** 2pt · **Deps:** TK-131, TK-141

Parse deadline from text.

**Acceptance:**
- ISO format: `/add 2026-06-15 14:00 Submit report`
- Natural: `tomorrow 9am`, `today 18:00`, `next Monday`, `in 2 hours`
- Parser in `DueDateParser` service. Regex/state machine for basic cases. LLM fallback deferred to TK-311.
- If date in the past — warning, but create
- Tests: 10+ format variants

---

### TK-133 (TYK-24) — /add with recurrence

**Epic:** Task Management · **Estimate:** 2pt · **Deps:** TK-132

Simple recurring patterns.

**Acceptance:**
- Support: `daily`, `weekly`, `weekdays`, `weekends`, `every Monday`
- Parser writes `recurrence_rule` as short string (`FREQ=DAILY` etc. — will become full RRULE in TK-201)
- On completeTask — create next instance (handled in TK-146)
- Tests for parsing

---

### TK-134 (TYK-25) — Views: /today, /overdue, /week, /done

**Epic:** Task Management · **Estimate:** 2pt · **Deps:** TK-114

Task views.

**Acceptance:**
- `/today` — tasks with due_at today (in user's TZ), grouped by list
- `/overdue` — due_at < now AND status = TODO
- `/week` — 7 days forward, grouped by day
- `/done <id>` — marks done
- Output formatted Markdown with priority emoji 🔴🟠🟡⚪

---

### TK-135 (TYK-26) — /edit, /snooze, /delete

**Epic:** Task Management · **Estimate:** 2pt · **Deps:** TK-114

Task modification.

**Acceptance:**
- `/edit <id> <field> <value>` — field: title/description/due/priority
- `/snooze <id> <duration>` — duration: `1h`, `2d`, `tomorrow`, `next week`
- `/delete <id>` — confirmation prompt → soft delete (archived_at)
- Errors: not found, not your task, invalid field

---

## Scheduling

### TK-141 (TYK-27) — Timezone: auto-detect + /tz override

**Epic:** Scheduling · **Estimate:** 1pt · **Deps:** TK-105

Correct TZ handling.

**Acceptance:**
- On /start: map `language_code` → default TZ (uk → Europe/Kyiv, en → UTC)
- `/tz <IANA>` — override (`/tz Europe/Warsaw`)
- `/tz` without arguments — shows current
- IANA validation via `ZoneId.of(...)` catching DateTimeException
- All timestamps in DB are UTC. Display in user's TZ

---

### TK-142 (TYK-28) — Quiet hours

**Epic:** Scheduling · **Estimate:** 1pt · **Deps:** TK-141

`/quiet` command.

**Acceptance:**
- `/quiet 22:00-07:00` — set period
- `/quiet off` — disable
- `/quiet` without arguments — show current
- Period can cross midnight (22:00–07:00 OK)
- `QuietHoursService.isQuiet(User, Instant)` to check

---

### TK-143 (TYK-29) — Quartz + ShedLock setup

**Epic:** Scheduling · **Estimate:** 1pt · **Deps:** TK-103

Distributed scheduler.

**Acceptance:**
- Quartz config with JDBC JobStore (Postgres)
- ShedLock via Spring annotation
- Flyway migration for quartz tables (official Quartz SQL for PostgreSQL)
- `@Scheduled` + `@SchedulerLock(name="...")` for cron methods
- Test: with two instances, one job runs only once

---

### TK-144 (TYK-30) — Morning digest cron (per-user TZ)

**Epic:** Scheduling · **Estimate:** 2pt · **Deps:** TK-143

Hourly check of users for morning digest.

**Acceptance:**
- Cron every hour (8:00–10:00 UTC covers most TZs)
- For each User: is it their selected hour now (default 08:00)?
- If yes — compose list of Project tasks for today with inline buttons
- Quiet hours: don't send during forbidden period
- Skip if list empty (optional)
- Configuration of digest hour: `/morning 8:00`

---

### TK-145 (TYK-31) — Reminder cron (+2h, +6h, +12h)

**Epic:** Scheduling · **Estimate:** 2pt · **Deps:** TK-143

Reminders on overdue.

**Acceptance:**
- Cron every 15 minutes
- For each overdue task — compute: should reminder be sent now?
- Intervals from due_at: +2h, +6h, +12h
- Respects quiet hours (postpones until end)
- ReminderLog table: don't send same level twice

---

### TK-146 (TYK-32) — Recurring expansion

**Epic:** Scheduling · **Estimate:** 2pt · **Deps:** TK-133

Create next instance after completion.

**Acceptance:**
- On `TaskService.complete(task)` — if has recurrence_rule, create new Task with next due_at
- In the same transaction
- Compute next date — separate `RecurrenceCalculator`
- Don't create if `archived_at` was set manually
- Tests: daily/weekly/weekdays

---

## Nudgers (key feature)

### TK-151 (TYK-33) — Nudger entity + escalation_policies + nudge_log

**Epic:** Nudgers · **Estimate:** 1pt · **Deps:** TK-103, TK-111

Migrations and entity classes.

**Acceptance:**
- `V5__nudgers_tables.sql`: nudgers, escalation_policies, nudge_log
- nudgers: id, owner_id FK, nudger_user_id FK, status (PENDING/ACTIVE/PAUSED), karma_score (INT default 0), added_at
- escalation_policies: id, target_type (TASK/LIST), target_id, level (INT), delay_minutes (INT), reveal_fields (VARCHAR — NUMBER/TITLE/DESCRIPTION)
- nudge_log: id, target_type, target_id, nudger_id FK, level, sent_at, acknowledged_at NULL, message_template TEXT
- Entity classes + repositories

---

### TK-152 (TYK-34) — /nudgers add @username (invite + deep-link)

**Epic:** Nudgers · **Estimate:** 2pt · **Deps:** TK-151

Nudger invitation.

**Acceptance:**
- `/nudgers add @username` looks up User by tg_username
- If found — creates Nudger with status PENDING and sends prompt to invitee
- If NOT found (user not yet registered) — generates deep-link `t.me/TykaloBot?start=nudge_invite_{base64payload}`, instruction "Send this link to them"
- On clicking the link — /start auto-handles the invite

---

### TK-153 (TYK-35) — Nudger consent: prompt + callback

**Epic:** Nudgers · **Estimate:** 1pt · **Deps:** TK-152

Invitee accepts/declines.

**Acceptance:**
- Invitee receives: "X invites you to be their Nudger. You'll get requests to remind them about tasks (first only the number, then title, then details). Agree?" + Yes/No buttons
- Callback `nudger:accept:{nudgerId}` → status ACTIVE, notify owner
- Callback `nudger:decline:{nudgerId}` → status REJECTED, notify owner
- Idempotent

---

### TK-154 (TYK-36) — /nudgers list/remove

**Epic:** Nudgers · **Estimate:** 1pt · **Deps:** TK-151

Manage existing Nudgers.

**Acceptance:**
- `/nudgers list` — list ACTIVE with karma_score
- `/nudgers remove @user` — confirmation → delete (soft or DB-cascade on nudge_log)
- `/nudgers pause @user` — status PAUSED (temp deactivation without removal)
- `/nudgers resume @user` — back to ACTIVE

---

### TK-155 (TYK-37) — Default escalation policy

**Epic:** Nudgers · **Estimate:** 1pt · **Deps:** TK-151

Default policy for Project tasks.

**Acceptance:**
- On creating Project task — auto-create 3 escalation_policy rows with target_type=TASK, target_id=task.id
- Level 1: delay=120 min, reveal=NUMBER
- Level 2: delay=360 min, reveal=TITLE
- Level 3: delay=720 min, reveal=DESCRIPTION
- Service `EscalationPolicyService.createDefaults(task)` + tests

---

### TK-156 (TYK-38) — Escalation cron

**Epic:** Nudgers · **Estimate:** 2pt · **Deps:** TK-155, TK-159

Trigger escalation.

**Acceptance:**
- Cron every 30 min
- For each overdue Project task with assigned Nudgers:
  - Determine which level should apply now (`now - due_at` vs `delay_minutes`)
  - For each ACTIVE Nudger: has this level been sent? (nudge_log)
  - If not — compose message per reveal_fields and send
  - Respect anti-fatigue limits (TK-159)
- Records to nudge_log

---

### TK-157 (TYK-39) — Nudger-side "I reminded" button

**Epic:** Nudgers · **Estimate:** 1pt · **Deps:** TK-156

Acknowledge from Nudger.

**Acceptance:**
- Each escalation message has inline button "✅ I reminded"
- Callback updates nudge_log.acknowledged_at, +1 to nudgers.karma_score
- Optionally: notify owner "X reminded you N times this month"

---

### TK-158 (TYK-40) — Per-task Nudger assignment

**Epic:** Nudgers · **Estimate:** 1pt · **Deps:** TK-155

Choose Nudgers for specific task.

**Acceptance:**
- On creating Project task — if no assigned, bot proposes choosing from active Nudgers
- `/task <id> nudgers @user1 @user2` — assign on existing task
- `/task <id> nudgers off` — disable for specific task (private)
- Table `task_nudgers (task_id, nudger_id)` many-to-many

---

### TK-159 (TYK-41) — Anti-fatigue limit

**Epic:** Nudgers · **Estimate:** 1pt · **Deps:** TK-156

Max N reminders/day per Nudger.

**Acceptance:**
- Default limit 3 messages/Nudger/day (configurable per user `nudger_daily_limit`)
- On limit reached — escalation moves to another active Nudger (round-robin) or postpones
- If all Nudgers maxed out — log warning, don't send
- Test: 5 simultaneous escalations, only 3 sent

---

## Deployment & Daily Use

### TK-171 (TYK-42) — /help command

**Epic:** Deployment · **Estimate:** 1pt · **No deps**

Help text.

**Acceptance:**
- `/help` — Markdown with all commands grouped: Lists / Tasks / Nudgers / Scheduling / Settings
- Each command — short description + example
- Inline buttons to Mini App heatmap (TK-233 when ready), links to docs

---

### TK-172 (TYK-43) — Onboarding on /start

**Epic:** Deployment · **Estimate:** 1pt · **Deps:** TK-105, TK-115

3-step intro for new users.

**Acceptance:**
- On first /start after creating User:
  1. Greeting + concept explanation (Nudgers)
  2. Let's create your first shopping list (inline button "Start")
  3. How to add Nudgers (inline button "Invite a friend")
- State machine in Redis: `onboarding_step` per user
- Skip-able

---

### TK-173 (TYK-44) — Telegram rate limit handler (queue with retry)

**Epic:** Deployment · **Estimate:** 2pt · **Deps:** TK-105

Outgoing message queue.

**Acceptance:**
- All outgoing SendMessage via `MessageQueueService` (not directly)
- Redis-based queue (List or Streams)
- Worker: 30 messages/sec global, 1/sec per chat
- On 429 from Telegram — exponential backoff retry
- If retry > 5 — log error, drop
- Dropped messages tracked in separate table for review

---

### TK-174 (TYK-45) — Deploy to VPS

**Epic:** Deployment · **Estimate:** 1pt · **Deps:** TK-107

Production deploy.

**Acceptance:**
- `Dockerfile` for backend (multi-stage, slim image)
- `docker-compose.prod.yml`: bot + Postgres + Redis + Caddy
- `Caddyfile` with auto-TLS for domain
- GitHub Actions deploy job: SSH → git pull → docker compose up -d --build
- Document VPS setup steps in DEPLOY.md
- Healthcheck endpoint monitored

---

### TK-175 (TYK-46) — Backup cron: pg_dump → S3/Backblaze

**Epic:** Deployment · **Estimate:** 1pt · **Deps:** TK-174

Daily backups.

**Acceptance:**
- Cron daily at 03:00 server time
- `pg_dump` → compressed → upload to Backblaze B2 (S3-compatible)
- Retention: 7 daily + 4 weekly + 12 monthly
- Backup verification: restore to temp DB and select count(*)
- Notification (Telegram → owner) if backup fails

---

# Phase 2 — Quality of Life

## Recurring & Routine

### TK-201 (TYK-47) — RRULE parser

**Epic:** Recurring & Routine · **Estimate:** 2pt · **Deps:** TK-133

Extended recurrence parser.

**Acceptance:**
- Support RFC 5545 RRULE subset: FREQ, INTERVAL, BYDAY, COUNT, UNTIL
- `RruleParser.parse(String) → Rrule` (record)
- `RecurrenceCalculator.next(Instant from, Rrule rule) → Instant`
- Tests: 15+ cases (daily, weekly with BYDAY, monthly nth, with COUNT/UNTIL)
- Replace simple parser from TK-133

---

### TK-202 (TYK-48) — Routine list type

**Epic:** Recurring & Routine · **Estimate:** 2pt · **Deps:** TK-112, TK-201

Routine = recurring as a whole list.

**Acceptance:**
- ROUTINE List has recurrence_rule (e.g. FREQ=WEEKLY;BYDAY=MO,WE,FR)
- On trigger — new Task instances created (copy from template), link to original
- Old sessions — archived_at + link to new instance
- `RoutineRunner` service triggered by Quartz cron

---

### TK-203 (TYK-49) — Routine schedule UI via dialog

**Epic:** Recurring & Routine · **Estimate:** 2pt · **Deps:** TK-202

Conversational setup.

**Acceptance:**
- On creating Routine — multi-step dialog
- Step 1: which days? (inline keyboard with MO/TU/WE/TH/FR/SA/SU toggleable)
- Step 2: at what time? (numeric input)
- Step 3: how often? (every week / every 2 weeks)
- Spring StateMachine for states (prepared in TK-241 — can partially reuse here)

---

### TK-204 (TYK-50) — Routine session tracking

**Epic:** Recurring & Routine · **Estimate:** 1pt · **Deps:** TK-202

Separate sessions table.

**Acceptance:**
- `V?__routine_sessions.sql`: id, list_id FK, scheduled_at, status (PENDING/ACTIVE/COMPLETED/MISSED), tasks_completed_count, tasks_total
- On generating new instance — session created
- On completion of all session tasks — status COMPLETED
- If next is scheduled but not all done — status MISSED

---

### TK-205 (TYK-51) — Routine escalation

**Epic:** Recurring & Routine · **Estimate:** 2pt · **Deps:** TK-204, TK-156

Session-level escalation, not per-task.

**Acceptance:**
- For ROUTINE with opt-in Nudgers — escalation triggers on session, not individual task
- Nudger message: "X didn't close today's Routine 'Gym' — 0/5 exercises" instead of 5 separate
- Extend `EscalationCron`: branch for ROUTINE list
- Config: when to consider session missed (e.g. 4 hours after scheduled)

---

## Templates

### TK-211 (TYK-52) — Template entity + 5 base templates

**Epic:** Templates · **Estimate:** 2pt · **Deps:** TK-112

Template library.

**Acceptance:**
- `Template` entity: id, name, type (ListType), description, items (JSON or separate table)
- Seed data via Flyway or @PostConstruct: gym, shopping, morning routine, weekly review, medications
- Items for gym: ["10 squats", "20 push-ups", "1 min plank", "30 crunches"]
- `is_public BOOLEAN` (for now only public; user templates in TK-213)

---

### TK-212 (TYK-53) — /template list/use

**Epic:** Templates · **Estimate:** 1pt · **Deps:** TK-211

Template commands.

**Acceptance:**
- `/template list` — list available templates
- `/template use <name>` — create new List from template items
- If template is ROUTINE — invoke dialog steps from TK-203
- Inline buttons on /template list for quick-use

---

### TK-213 (TYK-54) — Save existing list as template

**Epic:** Templates · **Estimate:** 1pt · **Deps:** TK-211

Custom templates.

**Acceptance:**
- `/template save <list_name>` — copy list structure as new Template (is_public=false, owner_id)
- On `/template list` — show user-owned templates in separate section
- Tests

---

## Google Calendar

### TK-221 (TYK-55) — Google OAuth2 + token storage

**Epic:** Google Calendar · **Estimate:** 2pt · **Deps:** TK-105

OAuth flow.

**Acceptance:**
- `/connect google` — generate auth URL with proper scopes (calendar.events, calendar.calendarlist.readonly)
- Callback endpoint `/oauth/google/callback` handles code → token
- Refresh_token stored encrypted (pgcrypto pgp_sym_encrypt)
- `GoogleAuthService` for authenticated requests (auto-refresh)
- `/disconnect google` — revoke + delete tokens

---

### TK-222 (TYK-56) — Calendar selection via dialog

**Epic:** Google Calendar · **Estimate:** 1pt · **Deps:** TK-221

Choose calendars.

**Acceptance:**
- After connect — list calendars via CalendarList API
- Multi-select inline keyboard (toggleable checkboxes)
- Save selection in `gcal_synced_calendars` (user_id, calendar_id)
- By default — primary calendar selected

---

### TK-223 (TYK-57) — One-way sync: tasks → events

**Epic:** Google Calendar · **Estimate:** 2pt · **Deps:** TK-221

Tasks → Calendar.

**Acceptance:**
- On createTask/updateTask/completeTask — sync to GCal events
- Create dedicated "Tykalo" calendar in user's account (createCalendar API)
- Event summary = task title, start_time = due_at, end_time = due_at + 30min default
- task.gcal_event_id stored for later updates
- DONE → event title prefix `✅`

---

### TK-224 (TYK-58) — Webhook subscription + renewal cron

**Epic:** Google Calendar · **Estimate:** 2pt · **Deps:** TK-221

Push notifications.

**Acceptance:**
- On connect — subscribe to events watch URL
- Subscription expires in 7 days — separate cron-job renews 1 day before expiration
- Webhook endpoint `/webhook/gcal/{userId}` handles change notifications
- X-Goog-Channel-Token validation

---

### TK-225 (TYK-59) — Two-way: events → tasks

**Epic:** Google Calendar · **Estimate:** 2pt · **Deps:** TK-222, TK-224

Calendar → Tasks (filtered).

**Acceptance:**
- On webhook — sync changes from selected calendars
- Import only events with `#tk` in summary or description
- Create Task with title (without `#tk`), due_at, description from event description
- Reverse gcal_event_id link
- On event deletion — task.archived_at

---

### TK-226 (TYK-60) — Conflict resolution + audit log

**Epic:** Google Calendar · **Estimate:** 1pt · **Deps:** TK-223, TK-225

Last-write-wins.

**Acceptance:**
- Sync_log table: id, target_type, target_id, side_won (BOT/GCAL), fields_changed (JSON), timestamp
- On concurrent change (updated_at within 5 sec) — log as conflict
- Default — last writer wins, but conflict visible in audit

---

## Stats & UX

### TK-231 (TYK-61) — Daily stats aggregation + streaks

**Epic:** Stats & UX · **Estimate:** 2pt · **Deps:** TK-114

Aggregation cron.

**Acceptance:**
- `V?__user_stats.sql`: user_id, date, completed_count, missed_count, streak_days
- Cron 00:05 (server) computes for previous day
- Streak: counts if on day with due_at — all tasks DONE; else resets to 0
- `StatsService.getStreak(userId)`, `.getWeekly(userId, weeks)`

---

### TK-232 (TYK-62) — /stats text summary

**Epic:** Stats & UX · **Estimate:** 1pt · **Deps:** TK-231

Current stats.

**Acceptance:**
- `/stats` — current streak, this week done/missed, best day this month
- Simple Markdown output
- "Heatmap" button → Mini App (Phase TK-233)

---

### TK-233 (TYK-63) — Telegram Mini App: heatmap

**Epic:** Stats & UX · **Estimate:** 5pt · **Deps:** TK-231

React Mini App.

**Acceptance:**
- `frontend/mini-app-heatmap/` (React + TS + Vite + Tailwind)
- Heatmap calendar GitHub-style (year squares, intensity = completion ratio)
- Auth via Telegram WebApp.initData → verify hash on backend → JWT
- API endpoint `GET /api/stats/heatmap?year=...`
- Deploy: build → static files served by Spring Boot or Caddy

---

### TK-241 (TYK-64) — FSM dialog for add/edit

**Epic:** Stats & UX · **Estimate:** 3pt · **Deps:** TK-122

Spring StateMachine for dialogs.

**Acceptance:**
- StateMachine config with states: IDLE, ADD_TITLE, ADD_DUE, ADD_PRIORITY, CONFIRM
- Trigger: `/add` without args starts flow
- Each step — inline keyboard with options or text input
- Cancel anytime with `/cancel`
- State stored in Redis per-user

---

### TK-242 (TYK-65) — Forward message → task

**Epic:** Stats & UX · **Estimate:** 1pt · **Deps:** TK-131

Quick capture.

**Acceptance:**
- Forwarded message handler: extract text → propose `Create task from this?` with Yes button
- On agree — create Task with title = text, in current list/Inbox
- If forward from channel — add source as description

---

### TK-243 (TYK-66) — Inline mode @TykaloBot

**Epic:** Stats & UX · **Estimate:** 2pt · **Deps:** TK-131

Inline mode for quick capture from other chats.

**Acceptance:**
- `@TykaloBot buy milk` shows single result "Add to Inbox: buy milk"
- On selection — create Task in user's Inbox
- Telegram inline query handler

---

# Phase 3 — AI Layer

### TK-301 (TYK-67) — LLM service abstraction

**Epic:** LLM Infrastructure · **Estimate:** 2pt · **No deps**

Provider pattern.

**Acceptance:**
- Interface `LlmService` with methods: `complete(SystemPrompt, UserMessage, Schema?)`, `embed(text)?`
- ClaudeProvider (anthropic-sdk) and GPT4oMiniProvider (openai-sdk) implementations
- Config `llm.primary=claude`, `llm.fallback=gpt4o-mini`
- On primary error — auto-fallback
- ENV: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`

---

### TK-302 (TYK-68) — Prompt versioning + Redis cache

**Epic:** LLM Infrastructure · **Estimate:** 2pt · **Deps:** TK-301

Prompts as code.

**Acceptance:**
- `prompts/` package with template files (`.txt` or `.md`)
- `PromptTemplate.load(name)` + interpolation
- Versioning in name: `nl_parser.v2.txt`
- Redis cache: key = sha256(prompt + input), TTL 24h for NL parsing, 7d for review
- Cache hit/miss metrics

---

### TK-303 (TYK-69) — Output schema validation

**Epic:** LLM Infrastructure · **Estimate:** 1pt · **Deps:** TK-301

Structured output.

**Acceptance:**
- Anthropic tool_use API for structured output
- JSON Schema validation with Jackson
- On schema violation — log + fallback to confirmation dialog
- Helper `LlmService.completeStructured(prompt, schema, class)` → typed result

---

### TK-304 (TYK-70) — Basic cost tracking

**Epic:** LLM Infrastructure · **Estimate:** 1pt · **Deps:** TK-301

Token usage tracking.

**Acceptance:**
- Table `llm_usage`: id, user_id, provider, model, input_tokens, output_tokens, cost_usd, timestamp
- Increment after each API call
- `/usage` — your stats for month
- Daily summary in logs (for personal monitoring)

---

### TK-311 (TYK-71) — NL parser: free text → structure

**Epic:** Natural Language Input · **Estimate:** 3pt · **Deps:** TK-301, TK-303

LLM-driven parser.

**Acceptance:**
- Schema: `{title: string, due_at: ISO datetime?, recurrence: RRULE?, priority?, list_hint?}`
- Prompt includes current time and user's TZ to resolve "tomorrow"
- First try regex parser (TK-132) — if success return; else LLM
- If LLM confidence low — confirmation dialog (TK-313)
- Tests with 20+ NL examples

---

### TK-312 (TYK-72) — Multi-task NL

**Epic:** Natural Language Input · **Estimate:** 2pt · **Deps:** TK-311

Batch creation.

**Acceptance:**
- Schema: `{tasks: [Task]}`
- Prompt: "add 3 tasks for tomorrow..."
- Bot returns parsed list + button "Create all / Edit"
- Created counts ≤ 10 at once

---

### TK-313 (TYK-73) — Confirmation dialog on ambiguity

**Epic:** Natural Language Input · **Estimate:** 2pt · **Deps:** TK-311

Clarifying questions.

**Acceptance:**
- LLM returns `ambiguity_options: [...]` when unclear
- Bot shows: "Tomorrow at 6 — is that 6am or 6pm?" + buttons
- On selection — continue with chosen variant
- Save preferred interpretation for future queries (optional)

---

### TK-321 (TYK-74) — Whisper API + voice handler

**Epic:** Voice · **Estimate:** 2pt · **Deps:** TK-301

Voice → text.

**Acceptance:**
- Telegram voice message handler
- Download file via getFile API
- Whisper API (openai whisper-1) → transcript
- Save transcript and audio file_id to logs (for debugging)
- Fallback gracefully if Whisper API unavailable

---

### TK-322 (TYK-75) — Voice → text → NL → task end-to-end

**Epic:** Voice · **Estimate:** 1pt · **Deps:** TK-321, TK-311

Integration.

**Acceptance:**
- Voice message → transcript → NL parser → task created
- Bot replies in text "Created: <task summary>"
- On ambiguity — voice → confirmation dialog

---

### TK-331 (TYK-76) — AI-generated Nudger messages

**Epic:** Smart Features · **Estimate:** 3pt · **Deps:** TK-301, TK-156

LLM generates escalation texts.

**Acceptance:**
- `NudgerMessageGenerator.generate(level, tone, task, ownerName) → text`
- Tone options: humor / friendly / aggressive (user setting `nudger_tone`)
- Prompt gives context: escalation level, owner name, what to show (NUMBER/TITLE/DESC)
- Caching variants to avoid API overuse
- Fallback to template if LLM unavailable

---

### TK-332 (TYK-77) — Task decomposition

**Epic:** Smart Features · **Estimate:** 2pt · **Deps:** TK-301

`/decompose <task_id>`.

**Acceptance:**
- LLM breaks down large task into 5-10 subtasks
- Schema: `{subtasks: [{title, estimate_hours, order}]}`
- Bot shows preview with buttons "Create all" / "Edit" / "Cancel"
- Creates tasks in same list with parent reference (parent_task_id)

---

### TK-333 (TYK-78) — Weekly AI review

**Epic:** Smart Features · **Estimate:** 2pt · **Deps:** TK-301, TK-231

Cron Sunday.

**Acceptance:**
- Cron sun 20:00 (user's TZ)
- Collect week stats: completed/missed, by category
- LLM generates: 3-4 review paragraphs + 2-3 reflective questions
- Inline button "Talk about it" (free-form chat) — optional
- User can disable via `/review off`

---

### TK-334 (TYK-79) — Bottleneck insights

**Epic:** Smart Features · **Estimate:** 2pt · **Deps:** TK-301, TK-231

`/insights`.

**Acceptance:**
- Analyze historical data: completion patterns by day-of-week, time-of-day, category
- LLM generates 2-3 insights with concrete examples
- Once a month auto-send or on-demand `/insights`
- Cache results — don't generate more than once per week

---

# Phase 4 — Family & Self-Motivation

### TK-411 (TYK-80) — Workspace entity + roles

**Epic:** Workspaces · **Estimate:** 2pt · **Deps:** TK-105

Workspace for shared use.

**Acceptance:**
- `V?__workspaces.sql`: workspaces (id, name, owner_id, type — FAMILY/TEAM, created_at), workspace_members (workspace_id, user_id, role — OWNER/MEMBER, joined_at)
- Entity classes
- User can be in 0..N workspaces

---

### TK-412 (TYK-81) — Shared lists with permissions

**Epic:** Workspaces · **Estimate:** 2pt · **Deps:** TK-411

List can belong to workspace.

**Acceptance:**
- ALTER lists: ADD workspace_id (nullable, FK)
- List is either owner-only or workspace-shared, not both
- Permissions: all members can see and edit, only OWNER can delete
- Notification: on adding/completing task — notify other members

---

### TK-413 (TYK-82) — Workspace invites via deep-link

**Epic:** Workspaces · **Estimate:** 2pt · **Deps:** TK-411

Invitations to workspace.

**Acceptance:**
- `/workspace invite @user` — generate deep-link `t.me/TykaloBot?start=ws_invite_{base64}`
- Or `/workspace create <name>` — create new
- On activation — prompt to invitee
- Multi-level: workspace can have multiple members

---

### TK-421 (TYK-83) — XP system + level progression

**Epic:** Self-Motivation · **Estimate:** 2pt · **Deps:** TK-114

Personal gamification.

**Acceptance:**
- On completeTask — increment XP by priority: LOW=10, MED=25, HIGH=50, URGENT=100
- ALTER users: ADD total_xp BIGINT default 0, level INT default 1
- Level threshold: `level^2 * 100 XP`
- `/level` — shows current level, total XP, progress to next
- Notification on level up

---

### TK-422 (TYK-84) — Badges/achievements

**Epic:** Self-Motivation · **Estimate:** 2pt · **Deps:** TK-421

Auto-detected achievements.

**Acceptance:**
- `V?__achievements.sql`: badges (id, code, name, description, icon), user_badges (user_id, badge_id, unlocked_at)
- Seed badges: "first_task", "100_tasks", "30_day_streak", "morning_bird" (10 tasks before 9am), "perfect_week"
- Auto-detection cron daily
- Notification on unlock + `/badges` list

---

### TK-423 (TYK-85) — Streaks notifications

**Epic:** Self-Motivation · **Estimate:** 1pt · **Deps:** TK-231

Positive reinforcement.

**Acceptance:**
- Cron: on milestone streak (7, 14, 30, 60, 100 days) — send congrats
- If streak broken after 14+ days — "Broke a streak of N days — start over?"
- Positive tone, not commanding
- User can disable `/streak_notifications off`

---

### TK-431 (TYK-86) — Pair-up flow + mutual dashboard

**Epic:** Mutual Accountability · **Estimate:** 2pt · **Deps:** TK-411

Accountability buddies.

**Acceptance:**
- `/pair @user` — proposal + two-sided agreement
- On agreement — record in pair_relations (user_a_id, user_b_id, started_at)
- `/pair stats` — comparative dashboard (your and their stats side by side)
- Privacy: only aggregated counts, not task details

---

### TK-432 (TYK-87) — Two-way Nudger pairs

**Epic:** Mutual Accountability · **Estimate:** 2pt · **Deps:** TK-431, TK-151

Auto-Nudger on pair.

**Acceptance:**
- On pair-up — both auto-become each other's Nudger (status ACTIVE without additional prompt)
- Fine tuning: `/pair levels` — which escalation levels to enable for partner
- Can disable separately in `/nudgers pause @user`

---

# Phase 5 — Personal Integrations

### TK-501 (TYK-88) — Notion two-way sync

**Epic:** Personal Integrations · **Estimate:** 5pt · **Deps:** TK-105

Notion database sync.

**Acceptance:**
- Notion OAuth integration
- User picks database via `/connect notion`
- Polling every 5 min (Notion API doesn't have webhooks)
- Mapping: Notion Title → task.title, Date → due_at, Select/Status → status, Priority → priority
- Conflict resolution: last-write-wins with audit log

---

### TK-502 (TYK-89) — Todoist import

**Epic:** Personal Integrations · **Estimate:** 2pt · **Deps:** TK-114

One-way import from CSV/JSON.

**Acceptance:**
- `/import todoist` — prompt to upload Todoist backup file
- Parse JSON or CSV export format
- Create tasks in Project-list "Imported from Todoist"
- No sync — only one-time

---

### TK-503 (TYK-90) — Google Tasks sync

**Epic:** Personal Integrations · **Estimate:** 2pt · **Deps:** TK-221

Google Tasks integration.

**Acceptance:**
- Reuse OAuth tokens from TK-221 (scope `tasks`)
- Bi-directional sync via polling every 10 min
- Each Google Tasks list → separate List in Tykalo
- Conflict resolution via updated timestamp

---

### TK-511 (TYK-91) — GitHub Issues → tasks

**Epic:** Personal Integrations · **Estimate:** 2pt · **Deps:** TK-105

GitHub integration.

**Acceptance:**
- GitHub OAuth with repo scope
- User picks repos via `/connect github`
- Webhook subscription to issues events
- Assigned to me → task in Project-list "GitHub: {repo}"
- Close issue → mark task DONE (and vice versa)

---

### TK-512 (TYK-92) — Jira integration

**Epic:** Personal Integrations · **Estimate:** 3pt · **Deps:** TK-105

Jira (if you have a work account).

**Acceptance:**
- Jira Cloud OAuth (oauth2-authorization-code flow)
- `/connect jira` + URL to Jira instance + auth
- JQL query: `assignee = currentUser() AND status != Done`
- Sync every 15 min
- Not bi-directional — read only

---

### TK-521 (TYK-93) — Apple Health: read steps/workouts

**Epic:** Personal Integrations · **Estimate:** 2pt · **Deps:** TK-105

Health data.

**Acceptance:**
- Via Apple HealthKit Web (iOS app companion) or Google Fit API
- Daily import: steps_count, active_minutes, workout_minutes
- `health_data` table: user_id, date, metrics JSON
- Data used for TK-522

---

### TK-522 (TYK-94) — Auto-complete fitness tasks

**Epic:** Personal Integrations · **Estimate:** 2pt · **Deps:** TK-521

Auto-completion on metrics.

**Acceptance:**
- On Task — new field `auto_complete_rule` (JSON): `{type: STEPS, threshold: 10000}` or `{type: WORKOUT_MINUTES, threshold: 30}`
- Cron checks Health data and auto-marks DONE
- UI: `/task <id> autocomplete steps 10000`
- Notification: "10k steps done — task 'Walk' auto-closed"

---

# Phase 6 — Multi-client (optional)

### TK-601 (TYK-95) — Web app skeleton

**Epic:** Multi-client · **Estimate:** 5pt · **Deps:** TK-105

React playground.

**Acceptance:**
- `frontend/web-app/` (React 18 + TS + Vite + Tailwind)
- Telegram Login Widget integration
- Backend `/api/auth/telegram` verify signature → JWT
- Routing: React Router. Pages: Login, Dashboard, Lists, Task detail
- API client with typed endpoints (axios + zod)

---

### TK-602 (TYK-96) — Web app: tasks/lists CRUD UI

**Epic:** Multi-client · **Estimate:** 8pt · **Deps:** TK-601

Full CRUD UI.

**Acceptance:**
- Sidebar with Lists (drag-and-drop reorder)
- Main panel: tasks of selected list
- Inline edit, drag-and-drop reorder/move between lists
- Markdown description rendering (react-markdown)
- Optimistic updates with React Query

---

### TK-603 (TYK-97) — Web app: dashboard with stats

**Epic:** Multi-client · **Estimate:** 5pt · **Deps:** TK-601, TK-231

Stats dashboard.

**Acceptance:**
- Large heatmap calendar (recharts or D3)
- Weekly completion rate chart
- Task category breakdown (pie chart)
- Streak counter prominently
- Responsive on mobile

---

### TK-604 (TYK-98) — Apple Watch quick-done complication

**Epic:** Multi-client · **Estimate:** 8pt · **Deps:** TK-105

iOS companion app + Watch complication.

**Acceptance:**
- Swift iOS app, Watch extension
- Auth via JWT from Telegram login
- Watch complication shows nearest task
- Tap → mark DONE
- ⚠️ Complex ticket — requires iOS development and App Store. Consider skipping in favor of TK-605.

---

### TK-605 (TYK-99) — PWA offline mode

**Epic:** Multi-client · **Estimate:** 5pt · **Deps:** TK-602

PWA with offline.

**Acceptance:**
- Service Worker for caching
- IndexedDB for offline storage
- Background Sync API — mutations applied when internet returns
- Web Push for notifications (from backend)
- Installable manifest with icons

---

# How to use

1. **One ticket at a time.** Don't dump 5 prompts on CC — it'll get confused.
2. **First confirm CC has read CLAUDE.md.** In a new session, say `read CLAUDE.md first`.
3. **Before each ticket** — recommend writing: "Working on TK-XXX (TYK-N). Branch off `main`. Read CLAUDE.md if not yet. Then [paste prompt]."
4. **Tests are part of the ticket** — mandatory policy in CLAUDE.md. If CC doesn't write tests — remind: "tests per CLAUDE.md policy".
5. **After code** — `./gradlew check` (build + test + lint) must pass before merge.
6. **On errors** — paste the full error into CC, it usually figures it out.
7. **Commit messages** — convention: `[TK-XXX] Short description`. Linear auto-links.
8. **On ticket completion** — move Linear ticket to Done, merge branch via PR.

# Useful CC commands

- `/init` — create CLAUDE.md in a new project (but we already have one — don't run it)
- `/clear` — fresh session when context fills up
- `/cost` — see tokens spent
- Reference CLAUDE.md explicitly: `read @CLAUDE.md`

# If you need to update CLAUDE.md

If you change decisions during work (stack, conventions, architecture) — come back to me and I'll update, or tell CC: "update CLAUDE.md to reflect [decision]".
