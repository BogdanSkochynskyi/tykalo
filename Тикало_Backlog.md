# Тикало — Personal Dev Backlog

Розбивка розробки для **особистого використання**. Без маркетингу, монетизації, комерційної інфраструктури. Фокус на тому, що покращує продукт для тебе як юзера.

**Загалом: 72 тікети через 6 фаз. До робочого MVP ~6–8 тижнів вечорами/вихідними.**

**Легенда:**
- **Priority:** 🔴 Critical · 🟠 High · 🟡 Medium · ⚪ Low (nice-to-have)
- **Estimate:** робочих днів (solo dev темп, full focus)
- **Tags:** `backend` · `frontend` · `devops` · `ai` · `integration`

---

## Phase 1 — Core MVP
**Goal:** працює end-to-end, можна користуватись щодня · **Duration:** 5 тижнів · **Tickets:** 27

### Epic: Foundation
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-101 | Bootstrap Spring Boot 3.x (Gradle, packages, конфіг) | 🔴 Critical | 0.5 | backend |
| TK-102 | Docker Compose: Postgres 16 + Redis для локалу | 🟠 High | 0.5 | devops |
| TK-103 | Flyway міграції + базова схема `users` | 🟠 High | 0.5 | backend |
| TK-104 | Реєстрація бота @BotFather, secret через .env | 🔴 Critical | 0.3 | devops |
| TK-105 | TelegramBots starter + `/start` handler + User entity | 🔴 Critical | 1.5 | backend |
| TK-106 | Logback structured JSON + Sentry (опційно — для дебагу) | 🟡 Medium | 0.5 | devops |
| TK-107 | GitHub Actions CI: build + test (мінімум) | 🟡 Medium | 0.5 | devops |

### Epic: Data Model & Lists
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-111 | Міграції: `lists`, `tasks`, `list_messages` | 🔴 Critical | 1 | backend |
| TK-112 | List entity з enum типів (CHECKLIST/ROUTINE/PROJECT/INBOX) | 🔴 Critical | 1 | backend |
| TK-113 | Task entity з опційними полями | 🔴 Critical | 1 | backend |
| TK-114 | Repository + Service layer (CRUD) | 🟠 High | 1 | backend |
| TK-115 | Автостворення Inbox при реєстрації | 🟠 High | 0.5 | backend |

### Epic: Checklist Mode (універсальний onboarding)
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-121 | Команди `/lists`, `/list create`, `/list delete` | 🔴 Critical | 1.5 | backend |
| TK-122 | `/use <name>` — current list context (per-user state в Redis) | 🔴 Critical | 1 | backend |
| TK-123 | Bulk-add: multi-line → N tasks у current list | 🔴 Critical | 1.5 | backend |
| TK-124 | Editable list message: рендеринг + inline keyboard | 🔴 Critical | 2 | backend |
| TK-125 | Callback handler ✅/❌ + Edit Message API | 🔴 Critical | 1.5 | backend |

### Epic: Task Management (Project mode)
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-131 | `/add` без дати (у current list) | 🔴 Critical | 1 | backend |
| TK-132 | `/add` з дедлайном (ISO + базові natural терміни: tomorrow, today 9am) | 🟠 High | 2 | backend |
| TK-133 | `/add` з recurrence (daily, weekly) | 🟠 High | 1.5 | backend |
| TK-134 | Перегляд: `/today`, `/overdue`, `/week`, `/done <id>` | 🟠 High | 1.5 | backend |
| TK-135 | `/edit`, `/snooze`, `/delete` | 🟡 Medium | 2 | backend |

### Epic: Scheduling
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-141 | Timezone: автодетект з Telegram + `/tz` override | 🔴 Critical | 1 | backend |
| TK-142 | Quiet hours: `/quiet 22:00-07:00` | 🟠 High | 0.5 | backend |
| TK-143 | Quartz + ShedLock setup | 🔴 Critical | 1 | backend |
| TK-144 | Ранкова розсилка cron (per-user TZ) | 🔴 Critical | 1.5 | backend |
| TK-145 | Reminder cron (+2h, +6h, +12h при простроченні) | 🔴 Critical | 1.5 | backend |
| TK-146 | Recurring expansion: створення наступного instance після виконання | 🟠 High | 1.5 | backend |

### Epic: Nudgers (the key feature)
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-151 | Nudger entity + escalation_policies + nudge_log таблиці | 🔴 Critical | 1 | backend |
| TK-152 | `/nudgers add @username` (invite + deep-link) | 🔴 Critical | 1.5 | backend |
| TK-153 | Згода Nudger-а: prompt + callback handler | 🔴 Critical | 1 | backend |
| TK-154 | `/nudgers list/remove` | 🟠 High | 0.5 | backend |
| TK-155 | Default escalation policy (3 рівні: +2h №, +6h title, +12h desc) | 🔴 Critical | 1 | backend |
| TK-156 | Escalation cron: trigger Nudger messages по рівнях | 🔴 Critical | 2 | backend |
| TK-157 | Nudger-side: "Я нагадав" кнопка + ack логування | 🟠 High | 1 | backend |
| TK-158 | Per-task assignment Nudger-ів при створенні задачі | 🟠 High | 1 | backend |
| TK-159 | Anti-fatigue: ліміт нагадувань/день одному Nudger-у | 🟠 High | 1 | backend |

### Epic: Deployment & Daily Use
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-171 | `/help` команда з усіма командами | 🟡 Medium | 0.5 | backend |
| TK-172 | Простий onboarding при `/start` (приклади) | 🟡 Medium | 0.5 | backend |
| TK-173 | Telegram rate limit handler (черга з retry) | 🟠 High | 1.5 | backend |
| TK-174 | Deploy на VPS (Hetzner CX22, Caddy + Docker Compose) | 🔴 Critical | 1 | devops |
| TK-175 | Backup cron: щоденний dump Postgres → S3/Backblaze | 🟠 High | 1 | devops |

---

## Phase 2 — Quality of Life
**Goal:** зручність щоденного використання · **Duration:** 4–5 тижнів · **Tickets:** 17

### Epic: Recurring & Routine
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-201 | RRULE parser (підмножина: daily/weekly/monthly з варіантами) | 🟠 High | 2 | backend |
| TK-202 | Routine list type: повтор цілого списку | 🔴 Critical | 2 | backend |
| TK-203 | Routine schedule UI через діалог (дні, час) | 🟠 High | 1.5 | backend |
| TK-204 | Routine session: трекінг кожного повтору окремо | 🟠 High | 1 | backend |
| TK-205 | Routine ескалація: "пропущена сесія" замість per-task | 🟠 High | 1.5 | backend |

### Epic: Templates
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-211 | Template entity + 5 базових (gym, shopping, ранкова, weekly review, ліки) | 🟠 High | 1.5 | backend |
| TK-212 | `/template list/use` команди | 🟠 High | 1 | backend |
| TK-213 | Save existing list as template | 🟡 Medium | 1 | backend |

### Epic: Google Calendar
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-221 | Google OAuth2 flow + encrypted token storage | 🟠 High | 2 | backend, integration |
| TK-222 | Calendar selection через діалог | 🟠 High | 1 | backend, integration |
| TK-223 | One-way sync: tasks → events у Тикало-календарі | 🟠 High | 2 | backend, integration |
| TK-224 | Webhook subscription + renewal cron | 🟠 High | 2 | backend, integration |
| TK-225 | Two-way: events → tasks (filter by tag) | 🟡 Medium | 2 | backend, integration |
| TK-226 | Conflict resolution + audit log | 🟡 Medium | 1 | backend |

### Epic: Stats & UX
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-231 | Daily stats aggregation + streaks | 🟠 High | 1.5 | backend |
| TK-232 | `/stats` команда з text summary | 🟡 Medium | 0.5 | backend |
| TK-233 | Telegram Mini App: heatmap calendar (React + TS) | 🟡 Medium | 4 | frontend |
| TK-241 | FSM dialog для add/edit замість тільки команд | 🟡 Medium | 3 | backend |
| TK-242 | Forward message → task | 🟡 Medium | 1 | backend |
| TK-243 | Inline mode `@TykaloBot ...` для quick-capture | ⚪ Low | 2 | backend |

---

## Phase 3 — AI Layer
**Goal:** розумна взаємодія, особисто корисні AI-фічі · **Duration:** 5–7 тижнів · **Tickets:** 13

Можна писати на Python окремим мікросервісом (твоя практика Python) або в самому Spring Boot.

### Epic: LLM Infrastructure
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-301 | LLM service abstraction (Claude API основний) | 🔴 Critical | 2 | backend, ai |
| TK-302 | Prompt versioning + Redis cache | 🟠 High | 1.5 | backend, ai |
| TK-303 | Output schema validation (JSON mode) | 🟠 High | 1 | backend, ai |
| TK-304 | Базовий cost tracking (для контролю токенів) | 🟡 Medium | 1 | backend, ai |

### Epic: Natural Language Input
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-311 | NL parser: free text → структура задачі | 🔴 Critical | 3 | backend, ai |
| TK-312 | Multi-task NL ("додай 3 задачі: ...") | 🟠 High | 2 | backend, ai |
| TK-313 | Confirmation dialog при ambiguity | 🟠 High | 1.5 | backend, ai |

### Epic: Voice
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-321 | Whisper API + voice message handler | 🟠 High | 2 | backend, ai |
| TK-322 | Voice → text → NL parser → task end-to-end | 🟠 High | 1 | backend, ai |

### Epic: Smart Features
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-331 | AI-генеровані Nudger messages (варіативні per-level) | 🟠 High | 2.5 | backend, ai |
| TK-332 | Task decomposition: великі → підзадачі (з confirm) | 🟡 Medium | 2 | backend, ai |
| TK-333 | Weekly AI review (неділя ввечері) | 🟡 Medium | 2 | backend, ai |
| TK-334 | Bottleneck insights ("ти зриваєш пʼятниці") | ⚪ Low | 2 | backend, ai |

---

## Phase 4 — Family & Self-Motivation
**Goal:** workspace для сім'ї + персональна гейміфікація · **Duration:** 3–4 тижні · **Tickets:** 8

### Epic: Workspaces
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-411 | Workspace entity + roles (owner/member) | 🟠 High | 2 | backend |
| TK-412 | Shared lists з permissions | 🟠 High | 2 | backend |
| TK-413 | Workspace invites через deep-link | 🟠 High | 1.5 | backend |

### Epic: Self-Motivation
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-421 | XP system + level progression (для себе, не публічно) | 🟡 Medium | 2 | backend |
| TK-422 | Badges/achievements (auto-detection rules) | 🟡 Medium | 2 | backend |
| TK-423 | Streaks notifications ("ти на streak 14 днів!") | 🟠 High | 1 | backend |

### Epic: Mutual Accountability
| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-431 | Pair-up flow + mutual dashboard | 🟡 Medium | 2 | backend |
| TK-432 | Двосторонні Nudger-pair-и (ти Nudger-иш партнера, він тебе) | 🟡 Medium | 1.5 | backend |

---

## Phase 5 — Personal Integrations
**Goal:** інтеграція з твоїми тулами · **Duration:** 4–6 тижнів · **Tickets:** 7

Обирай тільки ті, що реально використовуєш — інші пропусти.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-501 | Notion two-way sync (database) | 🟡 Medium | 4 | backend, integration |
| TK-502 | Todoist import (для міграції старих задач) | ⚪ Low | 2 | backend, integration |
| TK-503 | Google Tasks sync | 🟡 Medium | 2 | backend, integration |
| TK-511 | GitHub Issues → tasks (для devs) | 🟡 Medium | 2 | backend, integration |
| TK-512 | Jira integration (якщо є робочий) | ⚪ Low | 3 | backend, integration |
| TK-521 | Apple Health: read steps/workouts | 🟡 Medium | 2 | backend, integration |
| TK-522 | Auto-complete fitness tasks при досягненні метрик | 🟡 Medium | 1.5 | backend, integration |

---

## Phase 6 — Optional: Multi-client
**Goal:** альтернативні клієнти якщо захочеш · **Horizon:** довільний, коли є бажання · **Tickets:** 5

Це твій playground для практики React + TS і, можливо, мобільної розробки. Не обов'язково.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-601 | Web app skeleton (React + TS + Vite) — auth через Telegram Login | ⚪ Low | 5 | frontend |
| TK-602 | Web app: tasks/lists CRUD UI | ⚪ Low | 7 | frontend |
| TK-603 | Web app: dashboard зі stats і heatmap | ⚪ Low | 4 | frontend |
| TK-604 | Apple Watch quick-done complication (iOS companion) | ⚪ Low | 10 | mobile |
| TK-605 | PWA offline mode (sync через service worker) | ⚪ Low | 4 | frontend |

---

# Підсумок

| Phase | Tickets | Estimate (days) | Calendar (solo, full focus) |
|---|---|---|---|
| **Phase 1 — Core MVP** | **27** | **~36** | **5 тижнів** |
| Phase 2 — Quality of Life | 17 | ~30 | 4–5 тижнів |
| Phase 3 — AI Layer | 13 | ~24 | 5–7 тижнів |
| Phase 4 — Family & Motivation | 8 | ~14 | 3–4 тижні |
| Phase 5 — Integrations | 7 | ~17 | 4–6 тижнів |
| Phase 6 — Multi-client (опційно) | 5 | ~30 | flexible |
| **TOTAL (Phase 1-5)** | **72** | **~121** | **~6 місяців full-time** |

**Якщо part-time** (вечори + вихідні, реалістично ~2–3 год/день): **~10–14 місяців до Phase 5**.

**Критичний шлях (🔴 Critical):** TK-101, TK-104, TK-105, TK-111-113, TK-121-125, TK-131, TK-141, TK-143, TK-144, TK-145, TK-151-153, TK-155, TK-156, TK-174, TK-202, TK-301, TK-311.

**Поради:**
1. Не залипати на Phase 1 в спробі зробити "ідеально" — деплой і користуйся, навіть якщо багів багато
2. Phase 5 інтеграції — вибирай тільки ті, що реально використовуєш, інші лишай як "nice-to-have"
3. AI Layer (Phase 3) — добра нагода практикувати Python (можна окремий мікросервіс), або просто Spring AI starter
4. Phase 6 — playground для React + TS, не плануй заздалегідь, роби якщо є настрій
