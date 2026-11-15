import { describe, expect, it } from "vitest";
import type { SeatView } from "./api";
import { compareRowLabels, layoutSeatMap, placedSeats, seatLabel, SEAT_SIZE } from "./seatmap";

const GRADES = [
  { grade: "VIP", prefix: "V", rows: 10, price: 150_000 },
  { grade: "R", prefix: "R", rows: 20, price: 120_000 },
  { grade: "S", prefix: "S", rows: 20, price: 90_000 },
];

/** The seeded venue (requirements §1), in the API's order: grade text, row, seat (R, S, VIP). */
function venueSeats(): SeatView[] {
  let id = 1;
  const seats = GRADES.flatMap(({ grade, prefix, rows, price }) =>
    Array.from({ length: rows }, (_, r) =>
      Array.from({ length: 30 }, (_, n) => ({
        seatId: id++,
        grade,
        rowLabel: `${prefix}${String(r + 1).padStart(2, "0")}`,
        seatNo: n + 1,
        price,
        status: "AVAILABLE" as const,
      })),
    ).flat(),
  );
  return seats.sort((a, b) => a.grade.localeCompare(b.grade) || a.rowLabel.localeCompare(b.rowLabel) || a.seatNo - b.seatNo);
}

describe("layoutSeatMap", () => {
  const seats = venueSeats();
  const layout = layoutSeatMap(seats);
  const placed = placedSeats(layout);

  it("places all 1,500 seats exactly once", () => {
    expect(seats).toHaveLength(1500);
    expect(placed).toHaveLength(1500);
    expect(new Set(placed.map((p) => p.seat.seatId)).size).toBe(1500);
  });

  it("orders grade blocks VIP → R → S regardless of input order", () => {
    expect(layout.blocks.map((b) => b.grade)).toEqual(["VIP", "R", "S"]);
    expect(layout.blocks.map((b) => b.rows.length)).toEqual([10, 20, 20]);
    expect(layout.blocks.map((b) => b.price)).toEqual([150_000, 120_000, 90_000]);
    const ys = layout.blocks.map((b) => b.y);
    expect([...ys].sort((a, b) => a - b)).toEqual(ys);
  });

  it("orders rows by label and seats by number, top to bottom and left to right", () => {
    const vip = layout.blocks[0];
    expect(vip.rows.map((r) => r.rowLabel)).toEqual(Array.from({ length: 10 }, (_, i) => `V${String(i + 1).padStart(2, "0")}`));
    for (const block of layout.blocks) {
      for (let i = 1; i < block.rows.length; i++) expect(block.rows[i].y).toBeGreaterThan(block.rows[i - 1].y);
      for (const row of block.rows) {
        expect(row.seats.map((s) => s.seat.seatNo)).toEqual(Array.from({ length: 30 }, (_, i) => i + 1));
        for (let i = 1; i < row.seats.length; i++) expect(row.seats[i].x).toBeGreaterThan(row.seats[i - 1].x);
      }
    }
  });

  it("keeps seats inside the canvas and below the stage, without overlap", () => {
    const positions = new Set(placed.map((p) => `${p.x},${p.y}`));
    expect(positions.size).toBe(1500);
    for (const p of placed) {
      expect(p.x + SEAT_SIZE).toBeLessThanOrEqual(layout.width);
      expect(p.y + SEAT_SIZE).toBeLessThanOrEqual(layout.height);
      expect(p.y).toBeGreaterThan(layout.stage.y + layout.stage.height);
    }
  });

  it("handles an empty seat list", () => {
    const empty = layoutSeatMap([]);
    expect(empty.blocks).toEqual([]);
    expect(placedSeats(empty)).toEqual([]);
  });
});

describe("helpers", () => {
  it("compares row labels numerically within a prefix", () => {
    expect(["V10", "V2", "V01"].sort(compareRowLabels)).toEqual(["V01", "V2", "V10"]);
  });

  it("labels a seat as row-number", () => {
    expect(seatLabel({ rowLabel: "V01", seatNo: 12 })).toBe("V01-12");
  });
});
