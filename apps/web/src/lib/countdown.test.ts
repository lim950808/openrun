import { describe, expect, it } from "vitest";
import { countdown, formatRemaining, remaining } from "./countdown";

const target = "2026-11-28T11:00:00Z";
const at = (iso: string) => Date.parse(iso);

describe("remaining", () => {
  it("splits the time left into h/m/s", () => {
    expect(remaining(target, at("2026-11-28T09:58:59Z"))).toEqual({ totalMs: 3_661_000, hours: 1, minutes: 1, seconds: 1, done: false });
  });

  it("rounds partial seconds up so 00:00 means done", () => {
    const r = remaining(target, at(target) - 200);
    expect(r.seconds).toBe(1);
    expect(r.done).toBe(false);
  });

  it("clamps the past to zero", () => {
    expect(remaining(target, at(target) + 5000)).toEqual({ totalMs: 0, hours: 0, minutes: 0, seconds: 0, done: true });
  });
});

describe("formatRemaining", () => {
  it("uses mm:ss under an hour", () => {
    expect(countdown(target, at("2026-11-28T10:48:00Z"))).toBe("12:00");
    expect(countdown(target, at("2026-11-28T10:59:55Z"))).toBe("00:05");
  });

  it("uses h:mm:ss from an hour", () => {
    expect(countdown(target, at("2026-11-28T08:59:50Z"))).toBe("2:00:10");
  });

  it("shows days from 24 hours", () => {
    expect(countdown(target, at("2026-11-26T10:00:00Z"))).toBe("2일 1:00:00");
  });

  it("shows 00:00 when done", () => {
    expect(formatRemaining(remaining(target, at(target)))).toBe("00:00");
  });
});
