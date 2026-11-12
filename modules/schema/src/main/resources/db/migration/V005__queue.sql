-- Waiting queue record (JDBC area, ADR-0003 `queue_*`; design in ADR-0006). The queue itself lives in Redis
-- (`queue:{showtimeId}` ZSET); this table gets one row per entry. Polling never writes here: the admission
-- scheduler copies the last poll time from Redis when it admits or abandons a member.

create table queue_admission (
    id               bigint generated always as identity primary key,
    showtime_id      bigint      not null references showtime (id),
    member_id        bigint      not null,            -- no FK on purpose: hot path; bots use synthetic ids
    entered_at       timestamptz not null,            -- = ZSET score (Redis server TIME, microseconds)
    last_polled_at   timestamptz null,                -- copied from `queue:{showtimeId}:polled` at admit/abandon
    admitted_at      timestamptz null,
    admitted_seq     bigint      null,                -- admission order (fifo_violation_rate), from the sequence below
    abandoned_at     timestamptz null,
    token_expires_at timestamptz null,
    created_at       timestamptz not null default now()
);

create index ix_queue_admission_member on queue_admission (showtime_id, member_id);
-- Rows still waiting: the scheduler updates exactly one of these per admitted or abandoned member.
create index ix_queue_admission_open on queue_admission (showtime_id, member_id)
    where admitted_at is null and abandoned_at is null;

create sequence queue_admission_admitted_seq;
