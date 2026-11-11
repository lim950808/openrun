-- Booking hot path (JDBC area, ADR-0003). Seat status is derived (ADR-0004 §1):
-- seat_hold is a pre-provisioned inventory row per showtime seat, "free" when
-- reservation_id is null or expires_at < now() (lazy expiry).

create table reservation (
    id            bigint generated always as identity primary key,
    showtime_id   bigint      not null references showtime (id),
    member_id     bigint      not null,            -- no FK on purpose: hot path; bots use synthetic ids
    status        varchar(16) not null,            -- HOLDING · PAYING · CONFIRMED · EXPIRED · CANCELLED
    expires_at    timestamptz not null,
    total_amount  integer     not null default 0,
    strategy      varchar(32) not null,            -- lock strategy that created the hold (report dimension)
    held_at       timestamptz null,                -- wall clock right after the seats were claimed (oversell_occurred_count)
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

-- One active reservation (HOLDING · PAYING) per member per showtime (requirements §1, M1-4).
create unique index ux_reservation_active_member on reservation (showtime_id, member_id)
    where status in ('HOLDING', 'PAYING');
create index ix_reservation_holding_expiry on reservation (expires_at) where status = 'HOLDING';

create table reservation_seat (
    reservation_id bigint  not null references reservation (id),
    seat_id        bigint  not null references seat (id),
    price          integer not null,               -- price snapshot
    primary key (reservation_id, seat_id)
);

create index ix_reservation_seat_seat on reservation_seat (seat_id);

create table seat_hold (
    showtime_id    bigint      not null references showtime (id),
    seat_id        bigint      not null references seat (id),
    price          integer     not null,
    reservation_id bigint      null,
    member_id      bigint      null,
    expires_at     timestamptz null,
    primary key (showtime_id, seat_id)
);
