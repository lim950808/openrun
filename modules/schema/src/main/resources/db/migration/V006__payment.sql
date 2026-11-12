-- Payment hot path (JDBC area, ADR-0003; design in ADR-0007 §3, transitions in ADR-0004 §3).

create table payment (
    id                  bigint generated always as identity primary key,
    reservation_id      bigint      not null references reservation (id),
    attempt             integer     not null,
    order_id            varchar(64) not null unique,        -- or-{reservationId}-{attempt} (Toss: 6–64 chars)
    amount              integer     not null,                -- server-side amount = reservation.total_amount
    status              varchar(16) not null,                -- REQUESTED · CONFIRMING · CONFIRMED · FAILED · UNKNOWN · CANCELLED
    payment_key         varchar(200) null,
    approved_at         timestamptz null,
    failure_code        varchar(64) null,
    confirming_since    timestamptz null,
    unknown_since       timestamptz null,                -- set on → UNKNOWN and kept (unknown_count)
    unknown_resolved_at timestamptz null,                -- set when the payment leaves UNKNOWN (unknown_dwell_p99_s)
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique (reservation_id, attempt)
);

-- ADR-0004 invariant 3: at most one active payment per reservation.
create unique index ux_payment_active on payment (reservation_id)
    where status in ('REQUESTED', 'CONFIRMING', 'UNKNOWN', 'CONFIRMED');
-- Payment janitor scans (ADR-0007 §5 paths 2 and 3).
create index ix_payment_confirming on payment (status, confirming_since);
create index ix_payment_unknown on payment (status, unknown_since);

-- HoldExpiryJanitor's PAYING sweep (ADR-0007 §5 last paragraph).
create index ix_reservation_paying_expiry on reservation (expires_at) where status = 'PAYING';

-- Every webhook receipt that passed the signature check is stored, duplicates included (outcome DUPLICATE),
-- so webhook_duplicate_count is a query. Uniqueness of event_key holds among non-duplicate rows only.
create table payment_event_inbox (
    id               bigint generated always as identity primary key,
    event_key        varchar(128) not null,               -- eventId, else sha256(eventType|orderId|status|createdAt)
    event_type       varchar(64)  not null,
    order_id         varchar(64)  null,
    payment_key      varchar(200) null,
    pg_status        varchar(32)  null,
    transmission_id  varchar(128) null,                   -- recorded only: Toss issues a new one per retry
    received_at      timestamptz  not null default now(),
    applied_at       timestamptz  null,
    outcome          varchar(16)  null,                   -- APPLIED · STALE · DUPLICATE · IGNORED · ORPHAN
    payload          jsonb        not null
);

create unique index ux_inbox_event_key on payment_event_inbox (event_key)
    where outcome is null or outcome <> 'DUPLICATE';
create index ix_inbox_pending on payment_event_inbox (id) where applied_at is null;
create index ix_inbox_order on payment_event_inbox (order_id);

-- Compensation work for the confirm-expire race (ADR-0004 invariant 2). Written in the same transaction as the
-- CompensationRequested outbox event.
create table payment_compensation (
    payment_id   bigint       primary key references payment (id),
    requested_at timestamptz  not null default now(),
    attempts     integer      not null default 0,
    last_error   varchar(200) null,
    done_at      timestamptz  null
);
