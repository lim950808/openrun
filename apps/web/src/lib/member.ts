/** 간이 회원 (requirements §1, glossary 화면). Bots use ids ≥ 2,000,000,000, so 1..1,000,000,000 never collides. */

export const MEMBER_KEY = "openrun.memberId";
export const NICKNAME_KEY = "openrun.nickname";
export const LAST_RESERVATION_KEY = "openrun.lastReservationId";
export const MAX_MEMBER_ID = 1_000_000_000;

export function randomMemberId(random: () => number = Math.random): number {
  return Math.floor(random() * MAX_MEMBER_ID) + 1;
}

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    // Storage blocked (private mode): the id lives for this page only.
  }
}

let fallbackId: number | null = null;

/** The member id for this browser, issued on first call. */
export function getMemberId(): number {
  const stored = Number(read(MEMBER_KEY));
  if (Number.isInteger(stored) && stored >= 1 && stored <= MAX_MEMBER_ID) return stored;
  const id = fallbackId ?? randomMemberId();
  fallbackId = id;
  write(MEMBER_KEY, String(id));
  return id;
}

export function getNickname(): string {
  return read(NICKNAME_KEY) ?? "";
}

const nicknameListeners = new Set<() => void>();

/** For useSyncExternalStore: notified when this tab changes the nickname. */
export function subscribeNickname(listener: () => void): () => void {
  nicknameListeners.add(listener);
  return () => nicknameListeners.delete(listener);
}

export function setNickname(nickname: string): void {
  const trimmed = nickname.trim().slice(0, 20);
  write(NICKNAME_KEY, trimmed === "" ? null : trimmed);
  nicknameListeners.forEach((l) => l());
}

export function getLastReservationId(): string | null {
  return read(LAST_RESERVATION_KEY);
}

export function setLastReservationId(id: number | string): void {
  write(LAST_RESERVATION_KEY, String(id));
}
