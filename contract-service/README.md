# contract-service — сервис Договоров

## Стек

Java 21, Spring Boot 4.0.7, Liquibase 5.0, PostgreSQL 16, RabbitMQ 4.1, S3 (MinIO). `8080` — REST `/internal/v1`,
`/livez`, `/readyz`; `8081` — актуатор.

## О сервисе

По задаче Заявок выпускает через 1С:УСК договор КМ с доплатой или полис ЛМ без неё, хранит, отдаёт и удаляет вложения,
принимает акцепт и оплату (Ordering), отвечает Заявкам. Фоном правит ведущий (`leader_lock`).

## Таймауты

| Что | Значение | Переменная |
|---|---|---|
| соединение: база, справочник, Ordering, сервер токенов, S3 | 2 с | `DB_CONNECTION_TIMEOUT_MS`, `*_CONNECT_TIMEOUT` |
| ответ: справочник, Ordering, сервер токенов, S3; вызов S3 | 7 с, 10 с, 5 с, 10 с; 20 с | `*_READ_TIMEOUT`; `S3_CALL_TIMEOUT` |
| база: сокет, блокировка, запрос, простой | 60 с, 5 с, 30 с, 1 мин | `DB_SOCKET_TIMEOUT_SECONDS`, `DB_LOCK_TIMEOUT`, `DB_STATEMENT_TIMEOUT`, `DB_IDLE_IN_TRANSACTION_TIMEOUT` |
| брокер: подтверждение | 10 с | `OUTBOX_CONFIRM_TIMEOUT` |
| смена ведущего | 30 с | `LEADER_LOCK_TTL` |
| остановка | 30 с | нет |

## Если сосед недоступен

- Справочник: повторы `REFERENCE_MAX_RETRIES`, задача ждёт; ответ 1С:УСК — лестница `INBOX_RETRY_BACKOFF`, затем
  FAILED.
- База: приём на паузе (`INBOX_STORAGE_CHECK_INTERVAL`), разбор — по лестнице.
- Брокер: DOWN у `internalBroker`/`onecBroker`/`marketingBroker` — ждут его сообщения; нет очереди 1С:УСК —
  ERROR `…_UNDELIVERED`.
- Ordering, токены: заказ и счёт ждут, растёт `contracts_ordering_failures_total`.
- S3: вложения — 503 `STORAGE_UNAVAILABLE`, передача КМ ждёт.
- 1С:УСК молчит: SUBMITTED; дольше `…_AWAITING_THRESHOLD` — `contracts_*_awaiting_overdue`.
- Без соседей: `RABBITMQ_*_LISTENER_AUTO_STARTUP` и `PAYMENT_ENABLED` = `false` (обязательна); акцепт при `false`
  заказа не даст и после включения. С активными заказами оплату не выключают.

## Сообщения

Очереди: `rvl_requests_to_rvl_contracts_task-changes`, `rvl_contracts_to_rvl_requests_status-changes`,
`rvl_contracts_to_1c-usk_*`, `1c-usk_to_rvl_contracts_*` (`*` — `lm-policy-changes`, `km-contract-changes`). Брокер
маркетинга: `fixed` — `MARKETING_DOCUMENT_FIXES_EXCHANGE`, события — `MARKETING_ORDER_EVENTS_QUEUE`.

REST от Заявок: справка — `POST /requests/{id}/participants/{id}/attachments`, согласие —
`POST /policies/{id}/family-members/{id}/consents`, файл — `GET /attachments/{id}/content`, удаление —
`DELETE /attachments/{id}`, пакет — `GET /requests/{id}/package`, акцепт — `POST /contracts/{id}/acceptance`.

## Диагностика по базе

`inbox_messages`: NEW, PROCESSED, SKIPPED, FAILED; `outbox_messages`: NEW, PUBLISHED, FAILED; `contracts`,
`lm_policies`: NEW … ISSUED, ERROR, CANCELLED; `payment_orders`: AWAITING_CREATION, CREATED, REJECTED, EXPIRED,
CANCEL_PENDING, CANCELLED, CANCEL_REJECTED; `payment_invoices`: AWAITING_DOCUMENT, DOCUMENT_UNCONFIRMED, READY,
CHECKOUT, ISSUED, REJECTED, PAYMENT_ERROR, PAID, OVERDUE.

```sql
select message_id, event_type, error_message from inbox_messages where status = 'FAILED';
select message_id, source_queue, reason from inbox_quarantine;
select 'KM', insured_participant_id, status::text, error_code from contracts where individual_request_id = '<заявка>'
union all
select 'LM', participant_id, status::text, error_code from lm_policies where individual_request_id = '<заявка>';
```

## Документация

[Эксплуатация](docs/operations.md), [неполадки](docs/troubleshooting.md), [почему так](docs/design.md),
[как пишем код](docs/code-style.md), [версии](CHANGELOG.md).
