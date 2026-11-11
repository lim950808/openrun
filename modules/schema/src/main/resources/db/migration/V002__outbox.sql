-- Transactional outbox (JDBC area, ADR-0003). Column names follow the Debezium Outbox Event Router SMT.

create table outbox (
    id              bigint generated always as identity primary key,
    aggregate_type  varchar(64) not null,
    aggregate_id    varchar(64) not null,
    event_type      varchar(64) not null,
    event_id        uuid        not null unique,
    payload         jsonb       not null,
    created_at      timestamptz not null default now()
);

create index ix_outbox_created_at on outbox (created_at);
