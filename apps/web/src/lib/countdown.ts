/** Remaining time to a target instant, for 오픈 전 · hold 만료 · 입장 토큰 잔여 시간. */

export type Remaining = { totalMs: number; hours: number; minutes: number; seconds: number; done: boolean };

export function remaining(targetIso: string, now: number): Remaining {
  const totalMs = Math.max(0, Date.parse(targetIso) - now);
  const totalSeconds = Math.ceil(totalMs / 1000);
  return {
    totalMs,
    hours: Math.floor(totalSeconds / 3600),
    minutes: Math.floor((totalSeconds % 3600) / 60),
    seconds: totalSeconds % 60,
    done: totalMs === 0,
  };
}

const pad = (n: number) => String(n).padStart(2, "0");

/** "mm:ss" under an hour, "h:mm:ss" otherwise, "d일 h:mm:ss" from a day. */
export function formatRemaining(r: Remaining): string {
  if (r.hours >= 24) {
    const days = Math.floor(r.hours / 24);
    return `${days}일 ${r.hours % 24}:${pad(r.minutes)}:${pad(r.seconds)}`;
  }
  if (r.hours > 0) return `${r.hours}:${pad(r.minutes)}:${pad(r.seconds)}`;
  return `${pad(r.minutes)}:${pad(r.seconds)}`;
}

export function countdown(targetIso: string, now: number): string {
  return formatRemaining(remaining(targetIso, now));
}
