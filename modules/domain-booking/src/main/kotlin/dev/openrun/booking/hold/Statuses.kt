package dev.openrun.booking.hold

/**
 * M1 lock strategies compared by the benchmark (docs/requirements.md §1, glossary 락 전략).
 * `REDIS_LUA_OUTSIDE_TX` is an experiment variant of `REDIS_LUA`, not a default candidate (ADR-0005 §7).
 */
enum class LockStrategy { NAIVE, FOR_UPDATE, SKIP_LOCKED, CONDITIONAL_UPDATE, REDIS_LUA, REDIS_LUA_OUTSIDE_TX }

/** Reservation states, ADR-0004 §2. Transitions are conditional UPDATEs on these names. */
enum class ReservationStatus { HOLDING, PAYING, CONFIRMED, EXPIRED, CANCELLED }

/** Derived per-showtime seat status, ADR-0004 §1. Never stored. */
enum class SeatStatus { AVAILABLE, HELD, SOLD }
