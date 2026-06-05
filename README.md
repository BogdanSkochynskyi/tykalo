# Тикало

Telegram-бот для управління особистими задачами і списками з механікою градуйованого соціального тиску (Nudger-и).

Особистий проект, один користувач. Деталі контексту, домен-моделі і конвенцій — у [CLAUDE.md](./CLAUDE.md).

## Prerequisites

- **JDK 25** — Gradle toolchain автоматично завантажить через foojay-resolver, якщо локально немає
- **Docker + Docker Compose** — для Postgres/Redis локально (Phase 1+)
- **Git Bash** або PowerShell на Windows

## Quick start

```bash
# Build
./gradlew build

# Run (dev profile — default)
./gradlew bootRun

# Run with explicit profile
./gradlew bootRun --args='--spring.profiles.active=dev'

# Tests
./gradlew test
./gradlew check        # build + test + lint
```

Після старту: <http://localhost:8080/actuator/health> → `{"status":"UP"}`.

## Profiles

- `dev` (default) — verbose logging, health details exposed
- `prod` — minimal logging, health details hidden

Активний профіль: env `SPRING_PROFILES_ACTIVE` або CLI `--spring.profiles.active`.

## Tech stack (summary)

Java 25 · Spring Boot 4.0.x · Gradle 9.x · PostgreSQL 16 · Redis 7 · Quartz + ShedLock · Flyway.

Повний стек і версії — `gradle/libs.versions.toml` та [CLAUDE.md](./CLAUDE.md#tech-stack).
