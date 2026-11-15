/**
 * Typed wrappers for the openrun API. Browser calls go to the same origin (`/api/...`); next.config.ts rewrites them
 * to API_BASE_URL. 4xx/5xx never throw: callers switch on `ok` / `status` / `code`. Network errors become status 0.
 */

export type ApiResult<T> =
  | { ok: true; status: number; body: T }
  | { ok: false; status: number; code: string };

export type Showtime = {
  showtimeId: number;
  showTitle: string;
  venueName: string;
  startsAt: string;
  openAt: string;
  entryOpensAt: string;
  totalSeats: number;
};

export type QueueWaiting = { status: "WAITING"; visitorToken: string; ahead: number; enteredAt: string };
export type QueueRank = { status: "WAITING"; ahead: number };
export type QueueAdmitted = { status: "ADMITTED"; accessToken: string; accessTokenExpiresAt: string };
export type QueueOut = { status: "NOT_IN_QUEUE" };
export type QueueEnterBody = QueueWaiting | QueueAdmitted;
export type QueueMeBody = QueueRank | QueueAdmitted | QueueOut;

export type SeatStatus = "AVAILABLE" | "HELD" | "SOLD";
export type SeatView = {
  seatId: number;
  grade: string;
  rowLabel: string;
  seatNo: number;
  price: number;
  status: SeatStatus;
};

export type HoldResult = {
  reservationId: number;
  expiresAt: string;
  seats: { seatId: number; price: number }[];
  totalAmount: number;
  strategy: string;
};

export type PaymentStatus = "REQUESTED" | "CONFIRMING" | "UNKNOWN" | "CONFIRMED" | "FAILED" | "CANCELLED";
export type ReservationStatus = "HOLDING" | "PAYING" | "CONFIRMED" | "EXPIRED" | "CANCELLED";

export type PaymentView = {
  status: PaymentStatus;
  reservationStatus: ReservationStatus;
  orderId: string;
  paymentKey: string | null;
  approvedAt: string | null;
  amount: number;
  attempt: number;
  failureCode: string | null;
};

export type ReservationView = {
  reservationId: number;
  showtimeId: number;
  status: ReservationStatus;
  expiresAt: string;
  totalAmount: number;
  seatIds: number[];
  payment: PaymentView | null;
};

export type PaymentStart = { orderId: string; amount: number; attempt: number; customerKey: string };
export type PaymentConfig = { pg: string; clientKey: string };

export const FAKE_FAULTS = ["NONE", "DECLINE", "TIMEOUT", "TIMEOUT_THEN_INQUIRY_DOWN", "DELAY"] as const;
export type FakeFault = (typeof FAKE_FAULTS)[number];
export type FakePayment = { paymentKey: string; orderId: string; amount: number };

type Headers = Record<string, string>;

async function call<T>(method: string, path: string, headers: Headers = {}, body?: unknown): Promise<ApiResult<T>> {
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      cache: "no-store",
      headers: body === undefined ? headers : { ...headers, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    return { ok: false, status: 0, code: "NETWORK_ERROR" };
  }
  const text = await res.text();
  let parsed: unknown = null;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = null;
    }
  }
  if (res.ok) return { ok: true, status: res.status, body: parsed as T };
  const code =
    parsed && typeof parsed === "object" && "code" in parsed && typeof parsed.code === "string" ? parsed.code : `HTTP_${res.status}`;
  return { ok: false, status: res.status, code };
}

const bearer = (token: string): Headers => ({ Authorization: `Bearer ${token}` });
const member = (memberId: number): Headers => ({ "X-Member-Id": String(memberId) });

export const api = {
  showtimes: () => call<Showtime[]>("GET", "/api/showtimes"),
  showtime: (id: string | number) => call<Showtime>("GET", `/api/showtimes/${id}`),

  enterQueue: (showtimeId: string | number, memberId: number) =>
    call<QueueEnterBody>("POST", `/api/showtimes/${showtimeId}/queue`, member(memberId)),
  queueMe: (showtimeId: string | number, visitorToken: string) =>
    call<QueueMeBody>("GET", `/api/showtimes/${showtimeId}/queue/me`, bearer(visitorToken)),

  seats: (showtimeId: string | number, accessToken: string) =>
    call<SeatView[]>("GET", `/api/showtimes/${showtimeId}/seats`, bearer(accessToken)),
  hold: (showtimeId: string | number, accessToken: string, seatIds: number[]) =>
    call<HoldResult>("POST", `/api/showtimes/${showtimeId}/holds`, bearer(accessToken), { seatIds }),
  release: (reservationId: string | number, memberId: number) =>
    call<null>("DELETE", `/api/reservations/${reservationId}`, member(memberId)),

  reservation: (reservationId: string | number, memberId: number) =>
    call<ReservationView>("GET", `/api/reservations/${reservationId}`, member(memberId)),
  startPayment: (reservationId: string | number, memberId: number) =>
    call<PaymentStart>("POST", `/api/reservations/${reservationId}/payments`, member(memberId)),
  confirmPayment: (memberId: number, body: { orderId: string; paymentKey: string; amount: number }) =>
    call<PaymentView>("POST", "/api/payments/confirm", member(memberId), body),
  cancel: (reservationId: string | number, memberId: number) =>
    call<PaymentView>("POST", `/api/reservations/${reservationId}/cancel`, member(memberId)),
  paymentConfig: () => call<PaymentConfig>("GET", "/api/payments/config"),
  fakePgAuthorize: (body: { orderId: string; amount: number; fault: FakeFault }) =>
    call<FakePayment>("POST", "/api/fake-pg/payments", {}, body),
};
