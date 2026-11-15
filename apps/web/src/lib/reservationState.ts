/**
 * Reservation + latest payment → the screen the reservation page shows (ADR-0008 §1 결제·결과 row).
 *
 * | reservation | payment                 | screen     |
 * |-------------|-------------------------|------------|
 * | HOLDING     | none                    | HOLDING    |
 * | HOLDING     | FAILED                  | FAILED     | (hold kept: "다시 결제")
 * | PAYING      | REQUESTED / none        | PAYING     | (PG 인증 단계)
 * | PAYING      | CONFIRMING / UNKNOWN    | CONFIRMING | (승인 확인 중, polling)
 * | PAYING      | CONFIRMED               | CONFIRMING | (transient: reservation catches up)
 * | PAYING      | FAILED                  | FAILED     |
 * | CONFIRMED   | any                     | CONFIRMED  |
 * | EXPIRED     | any                     | EXPIRED    |
 * | CANCELLED   | any                     | CANCELLED  |
 */
import type { PaymentStatus, ReservationStatus } from "./api";

export type ScreenState = "HOLDING" | "PAYING" | "CONFIRMING" | "CONFIRMED" | "FAILED" | "EXPIRED" | "CANCELLED";

export function screenState(input: { reservationStatus: ReservationStatus; paymentStatus?: PaymentStatus | null }): ScreenState {
  const p = input.paymentStatus ?? null;
  switch (input.reservationStatus) {
    case "HOLDING":
      return p === "FAILED" ? "FAILED" : "HOLDING";
    case "PAYING":
      if (p === "CONFIRMING" || p === "UNKNOWN" || p === "CONFIRMED") return "CONFIRMING";
      if (p === "FAILED") return "FAILED";
      return "PAYING";
    case "CONFIRMED":
      return "CONFIRMED";
    case "EXPIRED":
      return "EXPIRED";
    case "CANCELLED":
      return "CANCELLED";
  }
}

/** Screens that poll the reservation until it moves. */
export function isPolling(state: ScreenState): boolean {
  return state === "CONFIRMING";
}
