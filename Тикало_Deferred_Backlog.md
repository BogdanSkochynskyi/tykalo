# Тикало — Deferred Backlog

Задачі, що **відкладені** на момент, коли (і якщо) проект перейде з особистого у щось більше. Кожна категорія має **тригер реактивації** — умова, за якої її треба переглянути.

**Загалом: 38 тікетів у 8 категоріях.**

---

## A. Discovery & Market Validation
**Тригер реактивації:** якщо вирішиш робити публічний продукт або шукати соінвесторів/команду.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D001 | Скласти discovery survey (Google Forms) | 🟡 | 1 | product |
| TK-D002 | Запостити в ADHD-спільноти (Reddit, FB groups) | 🟡 | 0.5 | marketing |
| TK-D003 | Провести 15–20 user interviews (30 хв each) | 🟠 | 5 | product |
| TK-D004 | Landing page з waitlist (Tally/Framer) | 🟠 | 2 | frontend |
| TK-D005 | Ad-кампанія landing ($200 Reddit/Instagram) | 🟡 | 1 | marketing |
| TK-D006 | Опитати власних контактів про готовність бути Nudger-ом | 🟠 | 1 | product |
| TK-D007 | OKR + go/no-go рішення | 🟠 | 0.5 | product |

---

## B. Marketing & Public Launch
**Тригер реактивації:** Phase 5+ завершено, продукт стабільний, є усвідомлене бажання робити публічним.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D101 | Product Hunt launch package (assets, copy, gif demos) | 🟠 | 2 | marketing |
| TK-D102 | Landing page revamp з фічами + соц. proof | 🟠 | 2 | frontend |
| TK-D103 | Маркетингова кампанія: ADHD-комʼюніті, інфлюенсери | 🟡 | 3 | marketing |
| TK-D104 | Beta launch coordination (50–100 юзерів) | 🟠 | 1 | product |
| TK-D105 | i18n EN translations (locale messages.properties) | 🟡 | 2 | content |
| TK-D106 | Локалізація додаткові мови (RU, PL, ES) | ⚪ | 4 | content |

---

## C. Monetization & Payments
**Тригер реактивації:** є 1000+ активних юзерів і вони просять платні фічі. Або юридичний статус ФОП/компанія для приймання платежів.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D201 | Pricing tiers (Free / Pro / Team) у коді з feature flags | 🟠 | 2 | backend |
| TK-D202 | Stripe Subscriptions integration | 🟠 | 3 | backend |
| TK-D203 | Telegram Stars як альтернативний канал | 🟡 | 2 | backend |
| TK-D204 | Trial flow + downgrade logic | 🟡 | 1.5 | backend |
| TK-D205 | A/B test framework для pricing | 🟡 | 2 | backend |
| TK-D206 | Юридичне оформлення (ФОП, ПДВ, terms of service) | 🟠 | 5 | legal |

---

## D. Bet Mode (Financial Accountability)
**Тригер реактивації:** є попит від юзерів І юридична компанія для роботи з фінансами. Складний скоп — не вмикати без консультації з юристом.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D301 | Юридичний рев'ю для регіонів запуску (анти-gambling regulation) | 🔴 | 2 | legal |
| TK-D302 | Stripe Connect + escrow logic | 🟠 | 3 | backend |
| TK-D303 | Charity option (програш → charity замість другу) | 🟠 | 1.5 | backend |
| TK-D304 | Dispute resolution flow | 🟡 | 1.5 | backend |

---

## E. Public API & Third-party Distribution
**Тригер реактивації:** є попит від розробників або власне бажання інтегрувати у інші системи.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D401 | Public REST API + OAuth2 для third-party | 🟡 | 5 | backend |
| TK-D402 | Zapier app | ⚪ | 3 | backend |
| TK-D403 | Webhook subscriptions для розробників | 🟡 | 2 | backend |
| TK-D404 | n8n/Make коннектори | ⚪ | 3 | backend |
| TK-D405 | API documentation site (Mintlify/Docusaurus) | ⚪ | 2 | frontend |

---

## F. Scale Infrastructure
**Тригер реактивації:** 1 інстанс не справляється з навантаженням (>5k активних юзерів) або є проблеми з доступністю. Поки solo + друзі — це не потрібно.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D501 | Kubernetes deployment (Helm charts) | 🟡 | 4 | devops |
| TK-D502 | RabbitMQ для async messaging + outbox pattern | 🟡 | 3 | backend, devops |
| TK-D503 | Multi-region setup planning | ⚪ | 2 | devops |
| TK-D504 | Database sharding strategy (partitioning by user_id) | ⚪ | 5 | backend |
| TK-D505 | Distributed tracing (OpenTelemetry + Jaeger) | ⚪ | 2 | devops |
| TK-D506 | Grafana dashboards + PagerDuty on-call | 🟡 | 2 | devops |
| TK-D507 | Read replicas + connection pooling tuning | ⚪ | 2 | devops |

---

## G. Multi-platform Expansion
**Тригер реактивації:** Telegram перестав влаштовувати (зміни API, регіональні обмеження) або є запит юзерів на інші платформи.

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D601 | WhatsApp Business API integration | ⚪ | 10 | backend |
| TK-D602 | Discord bot version | ⚪ | 7 | backend |
| TK-D603 | Smart home (Alexa, Google Home) voice integration | ⚪ | 7 | backend |
| TK-D604 | iMessage business / SMS fallback | ⚪ | 5 | backend |

---

## H. Social / Community Features
**Тригер реактивації:** є критична маса юзерів і вони хочуть взаємодіяти між собою (а не лише через приватних Nudger-ів).

| ID | Title | Priority | Est. | Tags |
|---|---|---|---|---|
| TK-D701 | Nudger leaderboard з кармою (публічні рейтинги "будильників") | ⚪ | 1.5 | backend |
| TK-D702 | Perks unlocked at levels (нові escalation styles при level-up) | ⚪ | 1.5 | backend |
| TK-D703 | Public commitment channels (Telegram channels де юзери звітують) | ⚪ | 3 | backend |
| TK-D704 | Strangers matching (як Focusmate buddy для незнайомців) | ⚪ | 4 | backend |
| TK-D705 | Community templates marketplace | ⚪ | 3 | backend, frontend |

---

# Підсумок

| Категорія | Тікетів | Estimate (days) | Потенційний тригер |
|---|---|---|---|
| A. Discovery | 7 | ~11 | Розглядаєш публічний продукт |
| B. Marketing & Launch | 6 | ~14 | Готовий йти публічно |
| C. Monetization | 6 | ~15.5 | 1000+ юзерів просять Pro |
| D. Bet Mode | 4 | ~8 | Є попит + юридична база |
| E. Public API | 5 | ~15 | Розробники просять інтеграції |
| F. Scale Infrastructure | 7 | ~20 | Single instance не справляється |
| G. Multi-platform | 4 | ~29 | Telegram перестав влаштовувати |
| H. Social/Community | 5 | ~13 | Критична маса юзерів |
| **TOTAL** | **44** | **~126** | — |

---

# Як цим користуватись

1. **Не дивись сюди в процесі розробки** — це contamination, відволікає від MVP
2. **Раз на рік** перечитуй — раптом якийсь тригер виявився актуальним
3. Якщо колись захочеш робити публічний продукт — починай з категорії **A (Discovery)** перш за все, не з marketing
4. Категорії **C-H мають критичну залежність** — Monetization без Marketing/Launch неможлива; Bet mode неможливий без Monetization
5. **F (Scale Infrastructure) — не починати завчасно**. Solo + друзі живуть на одному VPS роками. Це класична пастка over-engineering.

# Що НЕ потрапило в Deferred — і чому

Кілька фіч, які могли б здаватись "комерційними", але я лишив у основному backlog бо вони цінні особисто:

- **Family/Team workspace** (TN-411-413) — реально корисно для тебе з родиною
- **Mutual accountability** (TN-431-432) — корисно для тебе з другом
- **XP/Streaks/Badges** (TN-421-423) — особиста мотивація, не потребує спільноти
- **AI-функції** (Phase 3 цілком) — твоя якість життя в продукті
- **Інтеграції з твоїми тулами** (Phase 5) — особиста зручність
- **Web app + Watch app** (Phase 6) — твоя свобода користувати поза Telegram
