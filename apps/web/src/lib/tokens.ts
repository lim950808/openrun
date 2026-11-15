/** Visitor and access tokens per showtime in sessionStorage (ADR-0008 §1). Each entry carries its expiry. */

type Stored = { token: string; expiresAt: string | null; enteredAt?: string };

const key = (kind: "visitor" | "access", showtimeId: string | number) => `openrun.${kind}.${showtimeId}`;

function read(k: string): Stored | null {
  try {
    const raw = window.sessionStorage.getItem(k);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Stored;
    return typeof parsed.token === "string" ? parsed : null;
  } catch {
    return null;
  }
}

const listeners = new Set<() => void>();

/** For useSyncExternalStore: notified when this tab stores or clears a token. */
export function subscribeTokens(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function write(k: string, value: Stored | null): void {
  try {
    if (value === null) window.sessionStorage.removeItem(k);
    else window.sessionStorage.setItem(k, JSON.stringify(value));
  } catch {
    // Storage blocked: tokens live for this page only.
  }
  listeners.forEach((l) => l());
}

/** Visitor tokens last 3 hours server-side; the server tells us when one is expired, so no local expiry. */
export type VisitorToken = { token: string; enteredAt: string | null };

export function getVisitorToken(showtimeId: string | number): VisitorToken | null {
  const stored = read(key("visitor", showtimeId));
  return stored ? { token: stored.token, enteredAt: stored.enteredAt ?? null } : null;
}

export function setVisitorToken(showtimeId: string | number, token: string, enteredAt: string): void {
  write(key("visitor", showtimeId), { token, expiresAt: null, enteredAt });
}

export function clearVisitorToken(showtimeId: string | number): void {
  write(key("visitor", showtimeId), null);
}

export type AccessToken = { token: string; expiresAt: string };

/** The access token for the showtime, or null when missing or past its expiry. */
export function getAccessToken(showtimeId: string | number, now: number = Date.now()): AccessToken | null {
  const stored = read(key("access", showtimeId));
  if (!stored || !stored.expiresAt) return null;
  if (Date.parse(stored.expiresAt) <= now) return null;
  return { token: stored.token, expiresAt: stored.expiresAt };
}

let snapshot: { raw: string | null; value: AccessToken | null } = { raw: null, value: null };

/**
 * The stored access token without the expiry check, as a stable object for useSyncExternalStore (same object while
 * the stored value is unchanged). Callers compare `expiresAt` with their own clock.
 */
export function accessTokenSnapshot(showtimeId: string | number): AccessToken | null {
  let raw: string | null = null;
  try {
    raw = window.sessionStorage.getItem(key("access", showtimeId));
  } catch {
    raw = null;
  }
  if (raw !== snapshot.raw) {
    const stored = read(key("access", showtimeId));
    snapshot = { raw, value: stored?.expiresAt ? { token: stored.token, expiresAt: stored.expiresAt } : null };
  }
  return snapshot.value;
}

export function setAccessToken(showtimeId: string | number, token: string, expiresAt: string): void {
  write(key("access", showtimeId), { token, expiresAt });
}

export function clearAccessToken(showtimeId: string | number): void {
  write(key("access", showtimeId), null);
}
