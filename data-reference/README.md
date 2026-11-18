# data-reference

## Стек

Java 21, Spring Boot 4.0.7, PostgreSQL 16, Liquibase, RabbitMQ 4.1 (брокер 1С:УСК). Порт `8080` — REST и
пробы, `8081` — актуатор. К базе — 17 соединений на экземпляр. Версии —
[CHANGELOG](CHANGELOG.md).

## О сервисе

Справочники, полисы и связи застрахованных РВЛ ОНЛАЙН: принимает из 1С:УСК, отдаёт Заявкам и Договорам по REST;
в 1С не пишет. Реплик — до трёх: REST — на всех, очереди 1С, разбор и расписания — на ведущем; кэш сбрасывается
на всех, часы — по NTP.

## Таймауты

| Что | Значение | Переменная |
|---|---|---|
| Чтение по REST | 3 с | `API_READ_TIMEOUT_SECONDS` |
| Запросов REST одновременно, лишние — сразу 503 | 100 | `API_MAX_CONCURRENT_REQUESTS` |
| Ожидание соединения | 2 с | `DB_REST_CONNECTION_TIMEOUT_MS`, `DB_CONNECTION_TIMEOUT_MS` |
| Зависшая база: REST; приём и разбор | 5 с; 60 с | `DB_REST_SOCKET_TIMEOUT_SECONDS`; `DB_SOCKET_TIMEOUT_SECONDS` |
| Оператор; блокировка; простой транзакции | 30 с; 5 с; 1 мин | `DB_STATEMENT_TIMEOUT`; `DB_LOCK_TIMEOUT`; `DB_IDLE_IN_TRANSACTION_TIMEOUT` |
| Брокер: соединение; ответ на команду | 10 с; 30 с | —; `RABBITMQ_CHANNEL_RPC_TIMEOUT` |
| Роль после гибели ведущего | 30 с | `LEADER_LOCK_TTL` |
| Ожидание родителя | 24 ч | `ONEC_PARENT_WAIT_TIMEOUT` |
| Повторы разбора | 30 с, 5 мин, 30 мин | — |
| Прогрев | 30 с | `WARMUP_TIMEOUT` |
| Кэш справочников | 10 мин | `REFERENCE_CACHE_TTL` |
| Переподключение сброса кэша | 1–10 с | `REFERENCE_CACHE_RECONNECT_DELAY`, `…_MAX_DELAY` |

## Без базы и брокера

- Нет базы: кэш отдаётся, остальное — 503 через 2 с, у зависшей — до 5 с.
  Ведущего нет, сообщения ждут в очередях; приём вернётся сам, кэш сбросится целиком. Без базы не стартует.
- Нет брокера: сервис стартует и отвечает, приём ждёт. Без очередей 1С — `RABBITMQ_LISTENER_AUTO_STARTUP=false`.
  Пропала очередь — ведущий вернёт потребителя сам, раз в 30 с (`onec_ingest_queues_without_consumer`).
- `/livez`, `/readyz` от базы и брокера не зависят; `/readyz` — 200 после прогрева. Что лежит — `/actuator/health`:
  `db`, `rabbit`, `onecIngest` (`role`: `leader`/`standby`). У ведущего `onec_leader` = 1; метрики сырья — только у него.

## Сообщения и REST

Из 1С — 13 очередей по потребителю; подтверждение — после записи в базу.
Разбор — по порядку приёма, действует последнее; не наше — `SKIPPED` с причиной. Наружу —
`GET /internal/v1/…`: справочники, полисы и родственники застрахованного, [openapi](docs/openapi.yaml).

## Диагностика по базе

Сырьё — `inbound_message`, непринятые базой тела — `quarantine`. Статусы: `NEW` → `PROCESSED`, `SKIPPED`; `BUFFERED` —
ждёт сборки передачи; `AWAITING_PARENT` — ждёт родителя; `RETRY` — временный сбой; `FAILED` — нужен человек
(`last_error`).

```sql
select stream, status, count(*), min(received_at) from inbound_message
 where status not in ('PROCESSED', 'SKIPPED') group by 1, 2 order by 1, 2;

select stream, last_error, count(*), max(received_at) from inbound_message
 where status = 'FAILED' group by 1, 2 order by 3 desc limit 20;

select stream, awaited_parent_kind, awaited_parent_id, count(*), min(parent_wait_since) from inbound_message
 where status = 'AWAITING_PARENT' group by 1, 2, 3 order by 5 limit 20;
```

## Подробнее

- [troubleshooting](docs/troubleshooting.md) — известные проблемы.
- [operations](docs/operations.md) — настройки, выкатка, метрики, ручные операции.
- [design](docs/design.md) — почему так устроено; [code-style](docs/code-style.md) — как пишем код.
- [load-testing](docs/load-testing.md) — нагрузка.
