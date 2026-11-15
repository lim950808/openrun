import { describe, expect, it } from "vitest";
import type { PaymentStatus, ReservationStatus } from "./api";
import { isPolling, screenState, type ScreenState } from "./reservationState";

const table: [ReservationStatus, PaymentStatus | null, ScreenState][] = [
  ["HOLDING", null, "HOLDING"],
  ["HOLDING", "FAILED", "FAILED"],
  ["HOLDING", "CANCELLED", "HOLDING"],
  ["PAYING", null, "PAYING"],
  ["PAYING", "REQUESTED", "PAYING"],
  ["PAYING", "CONFIRMING", "CONFIRMING"],
  ["PAYING", "UNKNOWN", "CONFIRMING"],
  ["PAYING", "CONFIRMED", "CONFIRMING"],
  ["PAYING", "FAILED", "FAILED"],
  ["CONFIRMED", "CONFIRMED", "CONFIRMED"],
  ["CONFIRMED", null, "CONFIRMED"],
  ["EXPIRED", null, "EXPIRED"],
  ["EXPIRED", "FAILED", "EXPIRED"],
  ["EXPIRED", "REQUESTED", "EXPIRED"],
  ["CANCELLED", "CANCELLED", "CANCELLED"],
  ["CANCELLED", null, "CANCELLED"],
];

describe("screenState", () => {
  it.each(table)("reservation %s + payment %s → %s", (reservationStatus, paymentStatus, expected) => {
    expect(screenState({ reservationStatus, paymentStatus })).toBe(expected);
  });

  it("treats a missing payment like no payment", () => {
    expect(screenState({ reservationStatus: "HOLDING" })).toBe("HOLDING");
  });

  it("polls only while the approval is being confirmed", () => {
    const states: ScreenState[] = ["HOLDING", "PAYING", "CONFIRMING", "CONFIRMED", "FAILED", "EXPIRED", "CANCELLED"];
    expect(states.filter(isPolling)).toEqual(["CONFIRMING"]);
  });
});
