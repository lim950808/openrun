-- Catalog (JPA area, ADR-0003). Reference data with low write frequency.

create table venue (
    id          bigint generated always as identity primary key,
    name        varchar(200) not null,
    created_at  timestamptz  not null default now()
);

create table show (
    id          bigint generated always as identity primary key,
    title       varchar(200) not null,
    venue_id    bigint       not null references venue (id),
    created_at  timestamptz  not null default now()
);

create table showtime (
    id          bigint generated always as identity primary key,
    show_id     bigint      not null references show (id),
    starts_at   timestamptz not null,
    open_at     timestamptz not null,
    created_at  timestamptz not null default now()
);

create table seat (
    id          bigint generated always as identity primary key,
    venue_id    bigint      not null references venue (id),
    grade       varchar(8)  not null check (grade in ('VIP', 'R', 'S')),
    row_label   varchar(8)  not null,
    seat_no     integer     not null,
    constraint uq_seat_position unique (venue_id, row_label, seat_no)
);

create index ix_seat_venue on seat (venue_id);

create table member (
    id          bigint generated always as identity primary key,
    nickname    varchar(64) not null,
    created_at  timestamptz not null default now()
);
