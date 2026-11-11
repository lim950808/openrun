package dev.openrun.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/** Physical seat on a venue's seat map. Showtime-independent master data (glossary: 좌석). */
@Entity
@Table(name = "seat")
class Seat(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    var venue: Venue,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    var grade: SeatGrade,

    @Column(name = "row_label", nullable = false, length = 8)
    var rowLabel: String,

    @Column(name = "seat_no", nullable = false)
    var seatNo: Int,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
)
