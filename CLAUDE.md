# Tykalo — Claude Code Context

> This is the master context file for Claude Code. It's auto-loaded from the project root. Keep it up-to-date as decisions and conventions evolve.

## Project overview

**Tykalo** is a Telegram bot for managing personal tasks and lists, built around a graduated social-pressure mechanic (Nudgers).

**Core concept:** unfinished tasks escalate to trusted contacts — first the task number, then its title, then the full description.

**Universal model:** all tasks live inside `List` containers with types `CHECKLIST` / `ROUTINE` / `PROJECT` / `INBOX`. The type sets default behavior (Nudgers on/off, lifecycle, recurrence).

**Audience:** single user (personal project). No marketing, monetization, or public API.

**Issue tracker:** Linear, team `Tykalo`, project `Тикало`. Tickets are prefixed with `TK-XXX` in titles. Linear IDs are `TYK-N`.

## Tech stack

### Backend
- **Java 25 LTS** (records, sealed types, pattern matching, virtual threads, structured concurrency — all stable)
- **Spring Boot 4.0.6+** (latest stable, on Spring Framework 7, first-class Java 25 support). Min Java 17, up to Java 26.
- **Spring Framework 7** (bundled with Spring Boot 4 — modularization, JSpecify null-safety, API versioning, HTTP Service Clients)
- **Gradle 9.x** (build tool, full Java 25 support)
- **PostgreSQL 16+** (primary database)
- **Redis 7+** (cache, per-user state, Telegram rate-limit queue)
- **Quartz Scheduler 2.5+ + ShedLock 7.x** (cron jobs, distributed lock — ShedLock 7.x is the line that targets Java 17+ and Spring 7 / Boot 4; 6.x predates Boot 4)
- **Flyway 11.x** (DB migrations, versioned `V{n}__name.sql`)
- **Spring Data JPA 4.x + Hibernate 7.1+** (repositories, JPA 3.2)
- **org.telegram:telegrambots-springboot-longpolling-starter 10.0.0+** + **telegrambots-client 10.0.0+** (Telegram Bot API; new artifact layout in v10)
- **Jackson 2.18+** (JSON serialization, via Spring Boot BOM)
- **Lombok 1.18.36+** (latest version with Java 25 support)
- **JSpecify** for null-safety annotations (`@NullMarked`, `@Nullable`) — replaces legacy `@Nullable` from Spring/JSR-305

### AI Layer (Phase 3+)
- **Anthropic Claude API** primary, **OpenAI GPT-4o-mini** fallback
- Optionally a separate Python microservice (FastAPI) as a Python practice ground
- **OpenAI Whisper API** for voice → text

### Frontend (Phase 2+ Mini App, Phase 6 Web app)
- **React 18 + TypeScript + Vite**
- **Tailwind CSS** (utility-first)
- **Telegram WebApp SDK** for Mini Apps

### Infrastructure
- **Docker + Docker Compose** for local and production
- **Hetzner VPS (CX22)** for production
- **Caddy** as reverse proxy with auto-TLS
- **GitHub Actions** CI/CD
- **Backblaze B2** for backups

## Library version policy

**Always use the latest stable versions**, but go through the Spring Boot BOM where possible — it guarantees compatibility across 200+ transitive dependencies.

**Rules:**
1. **Spring Boot dependencies** — don't pin versions; inherit from the BOM.
2. **Non-Spring-managed** — pin the latest version in `gradle.properties` or `libs.versions.toml` (version catalog).
3. **Before major-version upgrades** — check release notes for breaking changes (Spring Boot 4 vs 3 in particular changed null-safety and modularization).
4. **Don't mix pre-releases** with production. Milestones/RC only in experimental feature branches.

**Recommended version catalog (`gradle/libs.versions.toml`):**
```toml
[versions]
spring-boot = "4.0.6"
telegram-bots = "10.0.0"
# Quartz is BOM-managed via spring-boot-starter-quartz — do NOT pin (Boot 4.0.6 pulls 2.5.0).
shedlock = "7.7.0"         # NOT in the Spring Boot BOM — pin it (7.x is the Java 17+ / Boot 4 line)
testcontainers = "2.0.5"   # NOT in the Spring Boot BOM — pin it (Boot 4.0.6 pulls 2.0.5 transitively)
jspecify = "1.0.0"
# Flyway is BOM-managed — do NOT pin. Pull it via the spring-boot-flyway module (see nuances below).

[libraries]
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }   # BOM-managed; needed for entities + Spring Data repositories
telegram-bots-starter = { module = "org.telegram:telegrambots-springboot-longpolling-starter", version.ref = "telegram-bots" }
telegram-bots-client = { module = "org.telegram:telegrambots-client", version.ref = "telegram-bots" }
spring-boot-starter-quartz = { module = "org.springframework.boot:spring-boot-starter-quartz" }   # BOM-managed; brings Quartz + autoconfig (clustered JDBC JobStore via spring.quartz.*)
shedlock-spring = { module = "net.javacrumbs.shedlock:shedlock-spring", version.ref = "shedlock" }
shedlock-provider-jdbc-template = { module = "net.javacrumbs.shedlock:shedlock-provider-jdbc-template", version.ref = "shedlock" }
spring-boot-flyway = { module = "org.springframework.boot:spring-boot-flyway" }
flyway-database-postgresql = { module = "org.flywaydb:flyway-database-postgresql" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
testcontainers-postgresql = { module = "org.testcontainers:testcontainers-postgresql" }
testcontainers-junit-jupiter = { module = "org.testcontainers:testcontainers-junit-jupiter" }
```

**Important Spring Boot 4 nuances:**
- **JSpecify replaces older nullability annotations.** Use `@NullMarked` at package-level, `@Nullable` for nullable values from `org.jspecify.annotations`. Don't mix with `org.springframework.lang.Nullable`.
- **Modularization** — Spring Boot 4 split monolithic JARs into smaller modules. If migrating from 3.x, update `build.gradle` to the new starter names.
- **Flyway autoconfig moved into its own module.** Raw `org.flywaydb:flyway-core` does NOT activate Flyway in Boot 4 — migrations silently never run. Depend on `org.springframework.boot:spring-boot-flyway` (it carries flyway-core + the autoconfiguration), plus `org.flywaydb:flyway-database-postgresql` (Flyway 11 needs the per-DB module for Postgres).
- **Testcontainers 2.0.x (what Boot 4.0.6 pulls) renamed everything.** Modules are now `org.testcontainers:testcontainers-postgresql` / `testcontainers-junit-jupiter` (was `postgresql` / `junit-jupiter`); the container class moved to `org.testcontainers.postgresql.PostgreSQLContainer` (the old `org.testcontainers.containers.*` one is deprecated → fails under `-Werror`) and is no longer generic (drop the `<?>`). Not in the Boot BOM — pin via `testcontainers-bom`.
- **Scheduling = two mechanisms (TK-143).** Quartz (`spring-boot-starter-quartz`, `spring.quartz.job-store-type=jdbc`, clustered) coordinates dynamically-scheduled persistent jobs (future per-task reminders/escalation); ShedLock (`@EnableSchedulerLock` + a `JdbcTemplateLockProvider.usingDbTime()`) guards fixed-interval `@Scheduled` sweep methods — each cron method gets a uniquely named `@SchedulerLock`. Flyway owns both schemas: V5 carries the official Quartz Postgres DDL (lowercased, `DROP`/`COMMIT` stripped so it runs inside Flyway's tx) plus the `shedlock` table; set `spring.quartz.jdbc.initialize-schema=never` so Quartz doesn't try to create its own. Copy `scheduling/HeartbeatJob` as the cron template.
- **Spring Framework 7 + Hibernate 7** — several JPA-API changes; never use deprecated `javax.persistence`, only `jakarta.persistence`.
- **Jackson 3 is the auto-configured mapper (TK-173).** Boot 4 ships Spring's HTTP/JSON stack on **Jackson 3**, whose mapper lives in the new `tools.jackson.databind` package — so the auto-configured bean is a `tools.jackson.databind.ObjectMapper`, and there is **no** `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2) bean in the context. Jackson 2 is still on the classpath transitively (e.g. telegrambots uses it), so code compiles, but `@Autowired`/constructor-injecting a Jackson-2 `ObjectMapper` fails at runtime with `NoSuchBeanDefinitionException` — and only surfaces in a real `@SpringBootTest` context, not in unit tests that `new ObjectMapper()`. Either inject the Jackson-3 mapper, or (as `telegram/ratelimit/RateLimitConfig` does) define your own `@Bean @ConditionalOnMissingBean ObjectMapper` for the Jackson-2 one. Prefer standardizing on the Jackson-3 `tools.jackson` mapper for new JSON work.
- **HTTP Service Clients** (new in 4.0) — declarative HTTP clients via annotations, an alternative to WebClient/RestTemplate. Consider for Google Calendar API and LLM providers.

## Project structure

```
tykalo/
├── CLAUDE.md                    # this file
├── README.md
├── docker-compose.yml
├── docker-compose.prod.yml
├── Dockerfile
├── Caddyfile
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
├── src/
│   ├── main/
│   │   ├── java/io/tykalo/
│   │   │   ├── TykaloApplication.java
│   │   │   ├── config/              # Spring config classes
│   │   │   ├── common/              # shared utilities, exceptions
│   │   │   ├── telegram/            # Bot framework, command dispatcher, handlers
│   │   │   │   ├── handler/         # @TelegramCommand annotated classes
│   │   │   │   ├── ratelimit/       # queue + retry logic
│   │   │   │   ├── conversation/    # Redis-backed menu conversation state (TK-187)
│   │   │   │   └── fsm/             # Spring StateMachine for dialogs (Phase 2+)
│   │   │   ├── user/                # User domain
│   │   │   │   ├── User.java        # entity
│   │   │   │   ├── UserRepository.java
│   │   │   │   ├── UserService.java
│   │   │   │   └── handler/         # /start, /tz, /quiet
│   │   │   ├── list/                # List + Task domain (tightly coupled)
│   │   │   ├── nudger/              # Nudger + escalation logic
│   │   │   ├── onboarding/          # First-/start 3-step onboarding (Redis-backed)
│   │   │   ├── scheduling/          # Quartz jobs (morning digest, reminders, escalation)
│   │   │   ├── ai/                  # LLM service abstraction (Phase 3+)
│   │   │   └── integration/         # GCal, Notion, GitHub etc. (Phase 2+)
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/        # Flyway: V1__users.sql, V2__lists_tasks.sql ...
│   └── test/
│       └── java/io/tykalo/
└── frontend/                        # Mini Apps + Web app (Phase 2+ and Phase 6)
    └── mini-app-heatmap/            # one Mini App per directory
```

**Package-by-feature.** Each feature (user, list, nudger) is its own package with its own entity/repository/service/handler. Avoid global services like `TaskManager` — keep things domain-specific (`ListService`, `TaskService`, `NudgerService`).

## Domain model

### Core entities

**User**
- `id` (UUID), `tgChatId` (Long), `tgUsername`, `timezone` (ZoneId), `quietHoursStart/End` (LocalTime), `locale`, `createdAt`, `morningHour` (int, 0-23, default 8), `nudgerDailyLimit` (int, default 3), `listChangeNotifications` (enum, default BATCHED — see Permission model)
- On first /start, auto-creates an `Inbox` List for them

**List**
- `id`, `ownerId` (User FK — legacy authority, see Permission model), `name`, `type` (enum), `recurrenceRule` (String, for ROUTINE), `nudgerDefaultPolicy` (enum), `archivedAt?`
- `type` enum: `CHECKLIST` (simple, no time, Nudgers off), `ROUTINE` (recurring as a whole, Nudgers opt-in), `PROJECT` (full tasks with deadlines, Nudgers per-task), `INBOX` (quick-capture, Nudgers off, default per-user list)
- Multi-user sharing via `ListMember` (Phase 1.5b, TK-191)

**ListMember** (Phase 1.5b)
- `id`, `listId` (FK), `userId` (FK), `role` (OWNER/EDITOR/MEMBER), `joinedAt`
- UNIQUE(list_id, user_id) — one role per user per list
- A list always has exactly one OWNER

**Task**
- `id`, `listId` (FK), `ownerId` (FK), `title` (only required!), `description?`, `dueAt?`, `priority?`, `status` (TODO/DONE/CANCELLED), `recurrenceRule?`, `gcalEventId?`, `tags[]`
- **Principle:** title-only = checkbox. With `dueAt + recurrence + nudgers` = full task with escalation.

**Nudger**
- `id`, `ownerId` (who invited), `nudgerUserId` (who is the Nudger), `status` (PENDING/ACTIVE/PAUSED), `karmaScore`, `addedAt`

**EscalationPolicy**
- `id`, `targetType` (TASK/LIST), `targetId`, `level`, `delayMinutes`, `revealFields` (NUMBER/TITLE/DESCRIPTION)
- Default: level 1 = +120 min, reveal NUMBER; level 2 = +360 min, reveal TITLE; level 3 = +720 min, reveal DESCRIPTION

**NudgeLog**
- `id`, `targetType`, `targetId`, `nudgerId`, `level`, `sentAt`, `acknowledgedAt?`, `messageTemplate`

### Key invariants
- **Time in DB is always UTC.** Display in user's TZ. Use `Instant` / `OffsetDateTime`, never `LocalDateTime` for storage.
- **Soft delete** for tasks and lists (`archivedAt` field) — so FK to nudge_log and task_completions doesn't break.
- **Nudgers require consent.** Don't allow escalation until status is ACTIVE.
- **Rate limits:** Telegram allows 30 msg/sec globally, 1 msg/sec per chat. All outgoing messages go through a Redis-backed queue.

## Permission model (Phase 1.5b)

Lists support multi-user sharing via `ListMember` with 3 roles:

| Role | Add items | Toggle done | Edit list (rename, change type) | Manage members | Delete list |
|------|-----------|-------------|---------------------------------|----------------|-------------|
| OWNER | ✅ | ✅ | ✅ | ✅ | ✅ |
| EDITOR | ✅ | ✅ | ✅ | ✅ (except OWNER) | ❌ |
| MEMBER | ✅ | ✅ | ❌ | ❌ | ❌ |

**Rules:**
- A list always has exactly one OWNER
- OWNER can transfer ownership; can't be removed by anyone — must transfer first
- Multiple EDITORs allowed
- Permission enforcement is at service boundary (`ListPermissionService`) — handlers don't check
- For Phase 1.5a (pre-TK-191), all lists are single-user (owner_id is implicit OWNER)
- TK-197 backfills `list_members` rows for existing lists (owner → OWNER role)

**Notification preferences** (`User.listChangeNotifications`):
- `INSTANT` — push on each change, aggregated 30s window per user/list ("@anna added 3 items")
- `BATCHED` (default) — 10-min window per (user, list), flushed at window end ("Changes in 'Groceries' (last 10 min): @anna added 4 items, @petro completed 2")
- `DAILY_DIGEST` — once per day at user's morning hour
- `OFF` — no push, only live list-message Edit Message API (TK-195)

Live message sync (TK-195) is always on regardless of preference — it's UI sync, not notification. Self-induced changes never produce push to the same user. Quiet hours respected for all push notifications.

## Coding conventions

### Java
- **Java 25 LTS features welcome:** records for DTO/value objects, sealed interfaces for domain types, pattern matching in switch, virtual threads for I/O-bound work (Telegram API, DB), structured concurrency where parallelism helps.
- **Lombok — use actively.** It's the standard tool, reduces boilerplate:
  - `@RequiredArgsConstructor` for Spring services (constructor injection)
  - `@Data` / `@Getter` / `@Setter` for JPA entities (careful with `@EqualsAndHashCode` on entities — use `@EqualsAndHashCode(of="id")`)
  - `@Builder` for complex factory methods
  - `@Slf4j` for loggers instead of `private static final Logger log = ...`
  - `@Value` for immutable DTO (if not a record)
  - **Avoid `@SneakyThrows`** — explicit exception handling is better.
- **Records vs Lombok @Value:** record for pure immutable value objects without extra logic; Lombok `@Value` when you need `@Builder` or `@With`.
- **Final by default** — locals and parameters. JPA entity fields are non-final (Hibernate requirement).
- **Constructor injection** via Lombok `@RequiredArgsConstructor`. No `@Autowired` field injection.
- **Optional&lt;T&gt;** for return values that may be null. Not for parameters, not for entity fields.
- **Domain events** via Spring `@EventListener` where appropriate (e.g., `TaskCompletedEvent` triggers recurring expansion).

### Naming
- Classes: `PascalCase`
- Packages: `lowercase` (no underscores)
- Methods/fields: `camelCase`
- Constants: `UPPER_SNAKE`
- Telegram commands: `/lowercase`, no dashes
- DB tables: `snake_case` plural (`users`, `tasks`, `nudge_log`)
- DB columns: `snake_case`
- Flyway: `V{N}__purpose.sql` (e.g., `V3__add_lists_tasks.sql`)

### Style
- **Imports:** no wildcards (`import java.util.*` — no), static imports OK for AssertJ and similar.
- **Line length:** 120 characters max.
- **Indent:** 4 spaces, not tabs.
- **Comments:** minimal, only when code isn't self-explanatory. No banner comments (`// === Section ===`). No comments restating what the code already shows.
- If a method is longer than 30 lines, try to break it up.

### Testing — MANDATORY POLICY

**Tests are written alongside code, no exceptions and no reminders needed.** If a ticket includes business logic, parsing, validation, escalation, scheduling, or sync — tests are required in the same commit/PR. A ticket is NOT considered done without tests.

**What MUST be tested:**
- Any domain logic (service methods with business rules)
- Parsers (NL, RRULE, due dates) — every supported format gets a test
- Cron jobs (via Spring `@SchedulerLock` and Testcontainers)
- Repositories with custom queries
- Telegram handlers — at minimum happy path and one error condition
- Integration flows: task creation → escalation → ack
- Migrations — implicitly via Testcontainers Postgres

**When tests may be minimal or absent (rare exceptions):**
- Pure config classes with no logic
- Trivial DTOs/records without methods
- Pure Spring annotations without custom behavior
- Documentation tickets (e.g. /help command — covered by a simple smoke test)
- DevOps tickets (Dockerfile, CI YAML) — verified manually

**Stack:**
- **JUnit 5 + AssertJ** — required. AssertJ for readable assertions: `assertThat(result).isEqualTo(...)`, not JUnit-style.
- **Mockito** for unit tests of services with external dependencies.
- **Testcontainers** for integration tests — Postgres, Redis. One base class (`AbstractIntegrationTest`), extend in tests. Use the **singleton-container pattern**: a `static final` container started in a `static {}` initializer with `@ServiceConnection` — NOT `@Testcontainers`/`@Container`. The `@Container` lifecycle stops the container in each class's `afterAll`, but Spring caches/shares the context across test classes, so the next integration class hits a dead port ("connection refused"). The singleton stays up for the JVM (Ryuk tears it down at exit). (Redis has no `@ServiceConnection` for a plain `GenericContainer`, so it's wired via `@DynamicPropertySource` setting `spring.data.redis.host/port` — same singleton lifecycle.)
- **The singleton Postgres is shared and never reset between integration-test classes.** Rows from one class persist into the next, so any unique column collides across classes. In particular `users.tg_chat_id` is UNIQUE — give each integration-test class its **own `tgChatId` range** and never reuse another class's numbers, or inserts fail with a constraint violation. Ranges already taken: `800_00x` (default escalation policy), `810_00x` (escalation cron), `900_00x`, `910_00x`, `920_00x`, `930_00x` (list messages), `940_00x` (morning digest query), `950_00x` (reminder service), `960_00x` (reminder log), `970_00x` (recurring expansion), `980_00x` (nudger entities), `990_00x` (nudger invite service), `1_000_00x` (per-task nudger assignment) — pick the next free `_00x` band for a new class.
- **WireMock** for external APIs (Telegram, Google Calendar, Anthropic, OpenAI).
- **@SpringBootTest** only when you genuinely need full context. For service logic, `@ExtendWith(MockitoExtension.class)` is enough.

**Test naming:** `should_doSomething_when_condition()` or `methodName_givenCondition_thenExpected()`. Pick one style, stick with it.

**Test quality:**
- One test, one behavior. If `// Act` has two steps, split into two tests.
- AAA pattern: `// Arrange`, `// Act`, `// Assert` sections are visible (comments optional if short).
- DON'T write tests "for coverage" or "tests for setters". Write tests where there's actual logic.
- Tests shouldn't be brittle: minimal mocking, real objects where possible.

**Coverage target:** ~70% for service layer, ~50% overall. Not a goal — an indicator. 5 quality tests of critical logic beat 50 getter tests.

### Database
- Any fields containing money, timestamps, IDs — `NOT NULL` where possible.
- Indexes on FKs and frequently-filtered fields (`status`, `due_at`, `owner_id`).
- Don't use `SELECT *` in JPQL — explicit fields or projections.
- Flyway migrations are **atomic and irreversible**. New column = new migration, don't combine with breaking changes.

### Telegram bot
- Each command handler is its own class with a method annotated `@TelegramCommand("/command")` (custom annotation, implemented in TK-105). Handler methods have the signature `String handle(Update)` — the returned text is sent as the reply (return `null` to stay silent). `TelegramCommandDispatcher` (a `BeanPostProcessor`) discovers them at startup and routes updates by command (case-insensitive, strips `@botname`). The dispatcher is pure routing — no Telegram-API dependency. `TykaloBot` (`SpringLongPollingBot`) owns the actual send and is gated by `telegram.bot.polling.enabled` (default true; set false in tests so no context polls Telegram).
- **Non-command (plain text) messages** — those that don't start with `/` — are routed to `MessageHandler` beans (`Optional<String> handle(Update)`, implemented in TK-123). The dispatcher collects them during the same `BeanPostProcessor` scan (it checks `bean instanceof MessageHandler`, not constructor injection — injecting beans into a `BeanPostProcessor` forces premature initialization) and consults them in registration order, sending the first non-empty reply. A handler returns `Optional.empty()` for messages it doesn't own, so unclaimed text stays silent. First use: `BulkAddHandler` (multi-line → N tasks). `/`-prefixed messages never reach message handlers.
- **Rich messages (keyboards, edit-in-place)** — the `String`/`Optional<String>` reply path only sends plain text and discards the sent message's id. When a handler needs an inline keyboard, the returned `message_id`, or an in-place edit, it goes through `TelegramMessageGateway` (`sendMarkdown`/`editMarkdown`, MarkdownV2) instead of returning a reply. The real impl is wired only when `telegram.bot.polling.enabled` is true; non-polling contexts (tests) get a `NoOpTelegramMessageGateway` fallback so dependent beans still construct. First use (TK-124): `ListMessageService` publishes a list's "live" editable message — one self-updating message per (list, chat), its `tg_message_id` stored in `list_messages` — and `ListRenderer` produces the MarkdownV2 body + per-task inline keyboard (a TODO task gets `✅` → `task:done:{taskId}`, a DONE task gets `↩️` → `task:undo:{taskId}`).
- **Outbound rate-limit queue (TK-173)** — every *new* outgoing message (`SendMessage`) is paced through a Redis-backed queue instead of being sent inline: `TelegramMessageGateway.sendMarkdown` and `TykaloBot`'s plain reply path both call `telegram/ratelimit/MessageQueueService.enqueue`, which `LPUSH`es a JSON `OutboundMessage` onto `telegram:outbound:queue`. A single dedicated virtual-thread worker (`OutboundQueueWorker`, started on `ApplicationReadyEvent`, polling-gated) `RPOP`s and delivers within Telegram's limits — 30/sec global (a per-second Redis counter) and 1/sec per chat (a short-lived `telegram:ratelimit:chat:{id}` key) — using the shared `TelegramClient`. No ShedLock: atomic `RPOP` + shared counters keep even a multi-instance deploy correct. A 429 schedules an exponential-backoff retry in a sorted-set (`telegram:outbound:retry`, scored by eligibility time, honoring `retry_after`); after `maxAttempts` (5) retries — or any permanent error — the message is written to `dropped_messages` (V14) for review. **Edits, callback answers, and the one id-capturing publish are NOT queued** (they aren't `SendMessage` and/or are latency-sensitive): `ListMessageService`'s first publish of a list's live message calls the synchronous `sendMarkdownDirect` so it can store the new `message_id`; that is one interactive send per (list, chat) and never bursty. Limits/backoff are tunable via `telegram.ratelimit.*` (`RateLimitProperties`).
- **Inline-button clicks (callback queries)** — routed to `CallbackHandler` beans (`Optional<String> handle(CallbackQuery)`, implemented in TK-125), collected by the dispatcher in the same `BeanPostProcessor` scan as message handlers. `dispatchCallback` returns the first non-empty result, which is the short toast text; `TykaloBot` always answers the callback afterwards (via `TelegramMessageGateway.answerCallback`) so Telegram's spinner stops even for unclaimed callbacks. First use: `TaskCallbackHandler` toggles a task via `TaskService.markDone`/`reopen` (both idempotent — a replayed click is a no-op) and refreshes the list message in place through `ListMessageService`. Toast text is plain (not MarkdownV2). **`callback_data` is capped at 64 bytes by Telegram** — a single 36-char UUID fits behind a short prefix, but a callback that must carry *two* ids (e.g. TK-158's `tn:a:{task}:{nudger}` per-task nudger picker) overflows it; pack each UUID to 22 chars with `io.tykalo.telegram.CompactUuid` (URL-safe Base64 of the 16 raw bytes) and decode on the way back.
- **Destructive commands use a stateless text confirmation** — until FSM dialog state (Phase 2+) exists, an irreversible action asks for confirmation by echoing the same command with a trailing `confirm` token rather than holding per-user state or rendering Yes/No inline buttons. `/<cmd> <target>` returns a prompt; `/<cmd> <target> confirm` performs the action. The target is re-resolved on the confirming call, so no state survives between the two messages. Used by `ListCommandHandler` (`/list delete <name> confirm`) and `TaskModificationCommandHandler` (`/delete <id> confirm`, TK-135).
- **First-`/start` onboarding (TK-172)** — a genuine first contact with no Nudger-invite deep-link kicks off a Redis-backed, skip-able 3-step flow owned by `onboarding/OnboardingService` (greeting + Nudgers concept → create a first Shopping list → how to add Nudgers). `UserService.register(Update)` returns `Registration(user, created)` so `StartCommandHandler` runs onboarding only on `created` (and stays silent — onboarding owns the messages via `TelegramMessageGateway`); invited and returning users are unaffected. One message evolves in place; state is the current `OnboardingStep` stored at `user:{id}:onboarding_step` (7-day TTL, same `StringRedisTemplate` pattern as `CurrentContextService`, no DB). Buttons land on `OnboardingCallbackHandler` under the `onb:` callback prefix (`onb:go`/`onb:list`/`onb:invite`/`onb:skip`); every transition is **guarded by the current step**, so a replayed/stale button after completion is a harmless no-op (e.g. "Create it" can't make a second list). Callback prefixes in use so far: `task:` (list toggles), `nudger:` (consent), `tn:` (per-task nudger picker), `onb:` (onboarding).
- FSM dialog states (Phase 2+) — Spring StateMachine.
- When there's no current list context — dispatcher picks Inbox as default.

## Linear status automation

Linear MCP is configured in Claude Code, so CC can update ticket status directly via the API. The status flow is:

`Backlog → In Progress (at step 5 of /ticket) → In Review (at step 7) → Done (after PR merge)`

**How CC should use Linear MCP:**
1. **Find the ticket** by its Linear identifier (TYK-N). Available tool: `mcp__linear__get_issue` with `query: "TYK-N"`.
2. **Find the target status** in team `Tykalo`. Available tool: `mcp__linear__list_issue_statuses` with `team: "Tykalo"`. Match by name (e.g., "In Progress").
3. **Update the ticket.** Available tool: `mcp__linear__save_issue` with the ticket identifier and the new state ID.

If a Linear MCP call fails (auth issue, network), CC should report the error and remind the user to update the status manually — never silently skip.

**Backup:** Linear-GitHub integration (Linear Settings → Integrations → GitHub) provides a fallback via commit/PR scanning. Commit messages must include `(TYK-N)` and PR descriptions should have `Closes TYK-N` for this to work. Both mechanisms coexist fine.

## Build / run / test

```bash
# Local development
docker-compose up -d                    # Postgres + Redis
./gradlew bootRun --args='--spring.profiles.active=dev'

# Tests
./gradlew test                          # unit + integration
./gradlew check                         # + lint, formatting

# Build
./gradlew build                         # produces build/libs/tykalo-*.jar
./gradlew bootJar                       # executable JAR

# Database
./gradlew flywayMigrate                 # apply migrations
./gradlew flywayInfo                    # status

# Docker
docker build -t tykalo .
docker-compose -f docker-compose.prod.yml up -d
```

## Important constraints

1. **Telegram Bot API rate limits** — 30 msg/sec global, 1 msg/sec per chat. All outgoing messages go through a queue. Never call SendMessage directly without the queue in production code.
2. **Postgres encoding** — UTF-8 with full Cyrillic+emoji support. Don't use VARCHAR without index size — TEXT is better.
3. **Quartz misfire policy** — for recurring jobs use `withMisfireHandlingInstructionFireAndProceed()` to avoid missing reminders after downtime.
4. **OAuth tokens (Google, Notion)** — encrypted at-rest. Use pgcrypto or Spring Security Crypto.
5. **Time in Telegram updates** — Unix timestamp; convert to Instant and store in UTC.
6. **Backups** — don't trust a single VPS. TK-175 does a daily dump to external S3.

## How to work on tickets

1. **Before writing any code** — read the full ticket description in Linear (TYK-N) and the corresponding prompt in `Tykalo_CC_Prompts.md`.
2. **Check dependencies** — many tickets reference earlier ones. Don't start TK-156 without TK-151.
3. **One ticket = one branch.** Branch name pattern: `bohdan/tk-{XXX}-{short-name}` where `XXX` is the TK ticket number (e.g., `bohdan/tk-101-bootstrap-spring-boot`). Lowercase, hyphens, no underscores.
4. **Commit messages and PR titles** must include the Linear ID (`TYK-N`) — Linear's git integration uses this to auto-move ticket status. Format: `[TK-XXX] (TYK-N) Short imperative description`. Example: `[TK-101] (TYK-5) Bootstrap Spring Boot 4.x project`.
5. **PR descriptions** should include `Closes TYK-N` on a separate line — this auto-moves the ticket to Done on merge.
6. **Tests are part of the ticket.** Not a separate ticket. Not a TODO. Same commit/PR. See Testing — MANDATORY POLICY above.
7. **Migrations** — new `V{N+1}__purpose.sql`, never edit previous ones.
8. **When done** — `./gradlew check` must pass (includes tests), then git push, open a PR (even solo — for history). Linear auto-moves the ticket if its git integration is configured; if not, also use the Linear MCP tools (see Linear status automation below) or move it manually.

**Definition of Done:**
- ✅ Code implements the ticket's acceptance criteria
- ✅ Tests cover the main logic (happy path + edge cases at minimum)
- ✅ `./gradlew check` passes (build, test, lint)
- ✅ Migrations apply cleanly on a fresh DB
- ✅ Documentation updated if public APIs or commands changed

## Code quality principles

- **Don't overengineer.** This is a personal project. Spring magic is fine, but don't spawn abstractions without cause.
- **Pragmatic over perfect.** If something works and is covered by 1-2 tests — it's good. Don't chase 100% coverage.
- **Domain first, infrastructure second.** Model the domain correctly first, then add Telegram/HTTP/Quartz wiring.
- **YAGNI.** Don't add abstractions for hypothetical future features — add them when you actually need them.
- **Fail fast.** Validation at the boundary (handler). Domain methods `assert` invariants.
- **Don't over-log.** Structured logs with JSON layout. INFO for business events, DEBUG for details, WARN for recoverable issues, ERROR for unrecoverable.

## Communication

**All Claude Code interactions (chat output, comments, commit messages, code, log messages) — in English.** The user reads them too, but English is cheaper in tokens and standard for codebases. Ukrainian only appears in user-facing strings if/when localization is implemented (Phase 2 — i18n).

## Quick reference

- **Current phase:** Phase 1.5 — Menu UX & Sharing (introduced after first live testing showed text commands weren't user-friendly and shared lists were critical for MVP)
- **Phase order:** 1 (Core MVP) → **1.5a (Menu UX, ~3-4w) → 1.5b (Shared Lists, ~2-3w)** → 2 (Quality of Life) → 3 (AI) → 4 (Workspaces, gamification) → 5 (Integrations) → 6 (Multi-client)
- **Linear project:** https://linear.app/tykalo/project/тикало
- **Ticket ID format:** TK-XXX (mine) ↔ TYK-N (Linear ID)
- **AI provider primary:** Anthropic Claude API
- **Hosting target:** Hetzner CX22 (€4/month)
