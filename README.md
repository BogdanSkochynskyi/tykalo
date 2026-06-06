# Тикало

Telegram-бот для управління особистими задачами і списками з механікою градуйованого соціального тиску (Nudger-и).

Особистий проект, один користувач. Деталі контексту, домен-моделі і конвенцій — у [CLAUDE.md](./CLAUDE.md).

## Prerequisites

- **JDK 25** — Gradle toolchain автоматично завантажить через foojay-resolver, якщо локально немає
- **Docker + Docker Compose** — для Postgres/Redis локально (Phase 1+)
- **Git Bash** або PowerShell на Windows

## Telegram bot setup (@BotFather)

The application requires a bot token and **will fail to start without one**.

1. Open [@BotFather](https://t.me/BotFather) in Telegram and send `/newbot`.
2. Choose a display name, then a unique username ending in `bot` (e.g. `TykaloBot`).
3. BotFather replies with an HTTP API **token** like `123456789:AAH...`. Copy it.
4. Put it into your local `.env` (gitignored — never commit it):

   ```bash
   cp .env.example .env       # once, if you haven't already
   # then set the value:
   TELEGRAM_BOT_TOKEN=123456789:AAH...
   ```

   In production, provide `TELEGRAM_BOT_TOKEN` as an environment variable instead of a file.

### Register the command list (`/setcommands`)

Send `/setcommands` to @BotFather, pick the bot, and paste the MVP command list:

```
start - Register and start using the bot
help - Show all commands grouped by area
lists - Show your lists
list - Create or delete a list
add - Add a task (optionally with date / recurrence)
today - Tasks due today
overdue - Overdue tasks
week - Tasks due this week
done - Mark a task done by id
nudgers - Manage your Nudgers (add / list / remove / pause / resume)
task - Per-task settings (e.g. assign Nudgers)
tz - Set or show your timezone
quiet - Set or show your quiet hours
```

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

## Локальні сервіси (Docker Compose)

Postgres 16 і Redis 7 для локальної розробки піднімаються через `docker-compose.yml`.

```bash
cp .env.example .env       # один раз — за потреби змінити креденшали
docker-compose up -d       # старт Postgres + Redis (з healthcheck-ами)
docker-compose ps          # статус і порти
docker-compose down        # зупинка (volume-и зберігаються)
docker-compose down -v     # зупинка + видалення даних
```

- **Postgres** — `localhost:5432`, база/користувач `tykalo` (пароль із `.env`).
- **Redis** — `localhost:6379`, без пароля.
- Дані персистяться у volume-ах `postgres_data` / `redis_data`.
- Порти конфігуруються через `POSTGRES_PORT` / `REDIS_PORT` у `.env`.

## Profiles

- `dev` (default) — verbose logging, health details exposed
- `prod` — minimal logging, health details hidden

Активний профіль: env `SPRING_PROFILES_ACTIVE` або CLI `--spring.profiles.active`.

## Tech stack (summary)

Java 25 · Spring Boot 4.0.x · Gradle 9.x · PostgreSQL 16 · Redis 7 · Quartz + ShedLock · Flyway.

Повний стек і версії — `gradle/libs.versions.toml` та [CLAUDE.md](./CLAUDE.md#tech-stack).
