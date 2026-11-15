/**
 * Pure seat map layout (ADR-0008 §1): grade blocks (VIP → R → S) → rows (V01, V02, …) → seats by number, each with
 * an x/y in SVG user units. The API orders seats by grade text (R, S, VIP), so the order here is decided by
 * [GRADE_ORDER], never by input order.
 */
import type { SeatView } from "./api";

export const GRADE_ORDER = ["VIP", "R", "S"] as const;

export const SEAT_SIZE = 14;
export const SEAT_GAP = 4;
export const SEAT_PITCH = SEAT_SIZE + SEAT_GAP;
/** Extra space after every [AISLE_EVERY] seats. */
export const AISLE_EVERY = 10;
export const AISLE_WIDTH = 12;
export const ROW_LABEL_WIDTH = 40;
export const MARGIN = 12;
export const STAGE_HEIGHT = 36;
export const BLOCK_HEADER = 26;
export const BLOCK_GAP = 18;

export type PlacedSeat = { seat: SeatView; x: number; y: number };
export type SeatRow = { rowLabel: string; y: number; seats: PlacedSeat[] };
export type GradeBlock = { grade: string; y: number; height: number; price: number; rows: SeatRow[] };
export type SeatMapLayout = { width: number; height: number; stage: { x: number; y: number; width: number; height: number }; blocks: GradeBlock[] };

export function gradeRank(grade: string): number {
  const i = (GRADE_ORDER as readonly string[]).indexOf(grade);
  return i === -1 ? GRADE_ORDER.length : i;
}

export function compareGrades(a: string, b: string): number {
  return gradeRank(a) - gradeRank(b) || a.localeCompare(b);
}

/** Row labels compare by their prefix, then numerically (V2 before V10 even without zero padding). */
export function compareRowLabels(a: string, b: string): number {
  const ma = /^(\D*)(\d+)$/.exec(a);
  const mb = /^(\D*)(\d+)$/.exec(b);
  if (ma && mb && ma[1] === mb[1]) return Number(ma[2]) - Number(mb[2]);
  return a.localeCompare(b);
}

export function seatX(seatNo: number): number {
  const index = seatNo - 1;
  return MARGIN + ROW_LABEL_WIDTH + index * SEAT_PITCH + Math.floor(index / AISLE_EVERY) * AISLE_WIDTH;
}

export function layoutSeatMap(seats: readonly SeatView[]): SeatMapLayout {
  const byGrade = new Map<string, Map<string, SeatView[]>>();
  for (const seat of seats) {
    const rows = byGrade.get(seat.grade) ?? new Map<string, SeatView[]>();
    byGrade.set(seat.grade, rows);
    const row = rows.get(seat.rowLabel) ?? [];
    rows.set(seat.rowLabel, row);
    row.push(seat);
  }

  const maxSeatNo = seats.reduce((m, s) => Math.max(m, s.seatNo), 1);
  const width = seatX(maxSeatNo) + SEAT_SIZE + MARGIN;
  const stage = { x: MARGIN + ROW_LABEL_WIDTH, y: MARGIN, width: width - 2 * MARGIN - ROW_LABEL_WIDTH, height: STAGE_HEIGHT };

  let y = MARGIN + STAGE_HEIGHT + BLOCK_GAP;
  const blocks: GradeBlock[] = [];
  for (const grade of [...byGrade.keys()].sort(compareGrades)) {
    const rows = byGrade.get(grade)!;
    const blockY = y;
    y += BLOCK_HEADER;
    const placedRows: SeatRow[] = [...rows.keys()].sort(compareRowLabels).map((rowLabel) => {
      const rowY = y;
      y += SEAT_PITCH;
      const rowSeats = [...rows.get(rowLabel)!].sort((a, b) => a.seatNo - b.seatNo);
      return { rowLabel, y: rowY, seats: rowSeats.map((seat) => ({ seat, x: seatX(seat.seatNo), y: rowY })) };
    });
    const price = placedRows[0]?.seats[0]?.seat.price ?? 0;
    blocks.push({ grade, y: blockY, height: y - blockY, price, rows: placedRows });
    y += BLOCK_GAP;
  }

  return { width, height: y - BLOCK_GAP + MARGIN, stage, blocks };
}

export function placedSeats(layout: SeatMapLayout): PlacedSeat[] {
  return layout.blocks.flatMap((b) => b.rows.flatMap((r) => r.seats));
}

/** "V01-12" */
export function seatLabel(seat: Pick<SeatView, "rowLabel" | "seatNo">): string {
  return `${seat.rowLabel}-${seat.seatNo}`;
}
