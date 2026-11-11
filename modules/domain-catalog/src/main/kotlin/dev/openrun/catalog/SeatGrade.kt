package dev.openrun.catalog

/** Seat grade and its price in KRW (docs/requirements.md §1). */
enum class SeatGrade(val price: Int) {
    VIP(150_000),
    R(120_000),
    S(90_000),
}
