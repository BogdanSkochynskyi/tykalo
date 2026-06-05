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
- **Quartz Scheduler 2.5+ + ShedLock 6.x** (cron jobs, distributed lock)
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
quartz = "2.5.0"
shedlock = "6.4.0"
flyway = "11.10.0"
testcontainers = "1.21.5"
jspecify = "1.0.0"

[libraries]
telegram-bots-starter = { module = "org.telegram:telegrambots-springboot-longpolling-starter", version.ref = "telegram-bots" }
telegram-bots-client = { module = "org.telegram:telegrambots-client", version.ref = "telegram-bots" }
quartz = { module = "org.quartz-scheduler:quartz", version.ref = "quartz" }
shedlock-spring = { module = "net.javacrumbs.shedlock:shedlock-spring", version.ref = "shedlock" }
```

**Important Spring Boot 4 nuances:**
- **JSpecify replaces older nullability annotations.** Use `@NullMarked` at package-level, `@Nullable` for nullable values from `org.jspecify.annotations`. Don't mix with `org.springframework.lang.Nullable`.
- **Modularization** — Spring Boot 4 split monolithic JARs into smaller modules. If migrating from 3.x, update `build.gradle` to the new starter names.
- **Spring Framework 7 + Hibernate 7** — several JPA-API changes; never use deprecated `javax.persistence`, only `jakarta.persistence`.
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
│   │   │   │   └── fsm/             # Spring StateMachine for dialogs (Phase 2+)
│   │   │   ├── user/                # User domain
│   │   │   │   ├── User.java        # entity
│   │   │   │   ├── UserRepository.java
│   │   │   │   ├── UserService.java
│   │   │   │   └── handler/         # /start, /tz, /quiet
│   │   │   ├── list/                # List + Task domain (tightly coupled)
│   │   │   ├── nudger/              # Nudger + escalation logic
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
- `id` (UUID), `tgChatId` (Long), `tgUsername`, `timezone` (ZoneId), `quietHoursStart/End` (LocalTime), `locale`, `createdAt`
- On first /start, auto-creates an `Inbox` List for them

**List**
- `id`, `ownerId` (User FK), `name`, `type` (enum), `recurrenceRule` (String, for ROUTINE), `nudgerDefaultPolicy` (enum)
- `type` enum: `CHECKLIST` (simple, no time, Nudgers off), `ROUTINE` (recurring as a whole, Nudgers opt-in), `PROJECT` (full tasks with deadlines, Nudgers per-task), `INBOX` (quick-capture, Nudgers off, default per-user list)

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
- **Testcontainers** for integration tests — Postgres, Redis. One base `@Testcontainers` class, extend in tests.
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
- Each command handler is its own class with `@TelegramCommand("/command")` (custom annotation, implemented in TK-105).
- FSM dialog states (Phase 2+) — Spring StateMachine.
- When there's no current list context — dispatcher picks Inbox as default.

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
3. **One ticket = one branch.** Branch name pattern: `bohdan/tk-{XXX}-{short-name}` where `XXX` is the TK ticket number (e.g., `bohdan/tk-101-bootstrap-spring-boot`, not `tk-5-...` from Linear's internal ID). Lowercase, hyphens, no underscores.
4. **Tests are part of the ticket.** Not a separate ticket. Not a TODO. Same commit/PR. See Testing — MANDATORY POLICY above.
5. **Migrations** — new `V{N+1}__purpose.sql`, never edit previous ones.
6. **When done** — `./gradlew check` must pass (includes tests), then git push, open a PR (even solo — for history), and move the Linear ticket to Done.

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

- **Current phase:** Phase 1 — Core MVP
- **Linear project:** https://linear.app/tykalo/project/тикало
- **Ticket ID format:** TK-XXX (mine) ↔ TYK-N (Linear ID)
- **AI provider primary:** Anthropic Claude API
- **Hosting target:** Hetzner CX22 (€4/month)
