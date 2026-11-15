"use client";

import Link from "next/link";
import Script from "next/script";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  api,
  FAKE_FAULTS,
  type ApiResult,
  type FakeFault,
  type PaymentConfig,
  type PaymentStart,
  type ReservationView,
  type SeatView,
} from "@/lib/api";
import { countdown } from "@/lib/countdown";
import { formatKst, formatWon } from "@/lib/format";
import { useMemberId, useNow } from "@/lib/hooks";
import { screenState, type ScreenState } from "@/lib/reservationState";
import { seatLabel } from "@/lib/seatmap";
import { getAccessToken } from "@/lib/tokens";

const CONFIRMING_POLL_MS = 3000;
const TOSS_SDK = "https://js.tosspayments.com/v2/standard";

type TossPaymentsFn = (clientKey: string) => {
  payment: (opts: { customerKey: string }) => {
    requestPayment: (req: {
      method: "CARD";
      amount: { currency: "KRW"; value: number };
      orderId: string;
      orderName: string;
      successUrl: string;
      failUrl: string;
    }) => Promise<void>;
  };
};

declare global {
  interface Window {
    TossPayments?: TossPaymentsFn;
  }
}

type Notice = { tone: "ok" | "warn" | "danger"; text: string };

const ERROR_TEXT: Record<string, string> = {
  AMOUNT_MISMATCH: "결제 금액이 예약 금액과 다릅니다. 결제가 승인되지 않았습니다.",
  HOLD_EXPIRED: "선점 시간이 지나 결제할 수 없습니다.",
  ILLEGAL_TRANSITION: "예약 상태가 바뀌어 요청을 처리하지 못했습니다. 최신 상태를 다시 불러왔습니다.",
  SHOW_STARTED: "공연이 이미 시작되어 취소할 수 없습니다.",
  PG_CANCEL_FAILED: "결제사 취소가 실패했습니다. 잠시 뒤 다시 시도해 주세요.",
  PAYMENT_NOT_FOUND: "결제 정보를 찾을 수 없습니다.",
};

const errorText = (code: string) => ERROR_TEXT[code] ?? `요청이 실패했습니다 (${code}).`;

/** 결제·결과 (ADR-0008 §1 row 3, M4-3). The reservation + payment state picks the screen. */
export default function ReservationPage() {
  const { reservationId } = useParams<{ reservationId: string }>();
  const router = useRouter();
  const memberId = useMemberId();
  const now = useNow(1000);
  const [result, setResult] = useState<ApiResult<ReservationView> | null>(null);
  const [config, setConfig] = useState<PaymentConfig | null>(null);
  const [pay, setPay] = useState<PaymentStart | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [busy, setBusy] = useState(false);
  const [seatViews, setSeatViews] = useState<Map<number, SeatView>>(new Map());
  const returnHandled = useRef(false);
  // Return from the Toss window, read once: successUrl carries paymentKey · orderId · amount, failUrl failed=1.
  const [tossReturn] = useState(() => (typeof window === "undefined" ? null : readTossReturn(window.location.search)));

  const load = useCallback((): Promise<void> => {
    if (memberId === null) return Promise.resolve();
    return api.reservation(reservationId, memberId).then(setResult);
  }, [memberId, reservationId]);

  useEffect(() => {
    if (tossReturn?.kind === "success") return; // the confirm below loads afterwards
    load();
  }, [load, tossReturn]);

  useEffect(() => {
    api.paymentConfig().then((r) => r.ok && setConfig(r.body));
  }, []);

  const reservation = result?.ok ? result.body : null;
  const state: ScreenState | null = reservation
    ? screenState({ reservationStatus: reservation.status, paymentStatus: reservation.payment?.status })
    : null;

  // Seat labels need the seat API, which needs an access token; without one the page shows seat ids.
  const showtimeId = reservation?.showtimeId;
  useEffect(() => {
    if (showtimeId === undefined) return;
    const access = getAccessToken(showtimeId);
    if (!access) return;
    api.seats(showtimeId, access.token).then((r) => r.ok && setSeatViews(new Map(r.body.map((s) => [s.seatId, s]))));
  }, [showtimeId]);

  useEffect(() => {
    if (state !== "CONFIRMING") return;
    const id = window.setInterval(load, CONFIRMING_POLL_MS);
    return () => window.clearInterval(id);
  }, [state, load]);

  const confirm = useCallback(
    (body: { orderId: string; paymentKey: string; amount: number }): Promise<void> => {
      if (memberId === null) return Promise.resolve();
      return api.confirmPayment(memberId, body).then((r) => {
        if (r.ok && r.status === 202) setNotice({ tone: "warn", text: "결제사 응답을 확인하고 있습니다. 화면이 자동으로 갱신됩니다." });
        else if (!r.ok) setNotice({ tone: r.status === 400 ? "danger" : "warn", text: errorText(r.code) });
        setPay(null);
        return load();
      });
    },
    [memberId, load],
  );

  useEffect(() => {
    if (memberId === null || tossReturn === null || returnHandled.current) return;
    returnHandled.current = true;
    window.history.replaceState(null, "", window.location.pathname);
    if (tossReturn.kind === "success") confirm(tossReturn.body);
  }, [memberId, tossReturn, confirm]);

  const startPayment = async () => {
    if (memberId === null) return;
    setBusy(true);
    setNotice(null);
    const r = await api.startPayment(reservationId, memberId);
    if (r.ok) setPay(r.body);
    else setNotice({ tone: "danger", text: errorText(r.code) });
    await load();
    setBusy(false);
  };

  const payWithFake = async (fault: FakeFault) => {
    if (!pay) return;
    setBusy(true);
    setNotice(null);
    const auth = await api.fakePgAuthorize({ orderId: pay.orderId, amount: pay.amount, fault });
    if (!auth.ok) {
      setNotice({ tone: "danger", text: `Fake PG 인증 실패 (${auth.code})` });
      setBusy(false);
      return;
    }
    await confirm({ orderId: pay.orderId, paymentKey: auth.body.paymentKey, amount: pay.amount });
    setBusy(false);
  };

  const payWithToss = async () => {
    if (!pay || !config || !window.TossPayments) {
      setNotice({ tone: "danger", text: "결제위젯을 불러오지 못했습니다. 새로고침 후 다시 시도해 주세요." });
      return;
    }
    const here = `${window.location.origin}${window.location.pathname}`;
    try {
      await window.TossPayments(config.clientKey)
        .payment({ customerKey: pay.customerKey })
        .requestPayment({
          method: "CARD",
          amount: { currency: "KRW", value: pay.amount },
          orderId: pay.orderId,
          orderName: `openrun 예약 ${reservationId}`,
          successUrl: here,
          failUrl: `${here}?failed=1`,
        });
    } catch (e) {
      setNotice({ tone: "danger", text: `결제창 오류: ${e instanceof Error ? e.message : String(e)}` });
    }
  };

  const release = async () => {
    if (memberId === null || !reservation) return;
    setBusy(true);
    const r = await api.release(reservationId, memberId);
    if (r.ok) {
      router.push(`/showtimes/${reservation.showtimeId}/seats`);
      return;
    }
    setNotice({ tone: "warn", text: errorText(r.code) });
    await load();
    setBusy(false);
  };

  const cancel = async () => {
    if (memberId === null) return;
    setBusy(true);
    const r = await api.cancel(reservationId, memberId);
    if (r.ok) setNotice({ tone: "ok", text: "전액 취소되었습니다." });
    else setNotice({ tone: "warn", text: errorText(r.code) });
    await load();
    setBusy(false);
  };

  if (result === null) return <main className="container muted">불러오는 중…</main>;

  if (!result.ok) {
    return (
      <main className="container">
        <section className="card" data-state="error">
          <h1>
            {result.status === 404
              ? "예약을 찾을 수 없습니다"
              : result.status === 403
                ? "다른 회원의 예약입니다"
                : "예약을 불러오지 못했습니다"}
          </h1>
          <p className="muted mono">{result.code}</p>
          <Link href="/">회차 목록으로</Link>
        </section>
      </main>
    );
  }

  const r = result.body;
  const holdLeft = now === 0 ? null : countdown(r.expiresAt, now);
  const holdOver = now !== 0 && Date.parse(r.expiresAt) <= now;
  const queueHref = `/showtimes/${r.showtimeId}/queue`;

  return (
    <main className="container">
      {config?.pg === "toss" && <Script src={TOSS_SDK} strategy="afterInteractive" />}
      <h1>예약 #{r.reservationId}</h1>
      {notice === null && tossReturn?.kind === "failed" && (
        <div className="notice notice-danger" role="status">
          결제가 완료되지 않았습니다. {tossReturn.message}
        </div>
      )}
      {notice && (
        <div className={`notice notice-${notice.tone}`} role="status">
          {notice.text}
        </div>
      )}

      <section className="card" data-state={state ?? undefined} data-testid="reservation-state">
        {state === "HOLDING" && (
          <>
            <h2>
              <span className="badge badge-warn">선점 중</span> 결제를 기다리고 있습니다
            </h2>
            <p>선점 만료까지</p>
            <p className="big">{holdLeft ?? "--:--"}</p>
            {holdOver && <p className="muted">선점 시간이 지났습니다. 곧 만료 처리됩니다.</p>}
            <Summary reservation={r} seats={seatViews} />
            <div className="row">
              <button className="btn btn-primary" type="button" disabled={busy || holdOver} onClick={startPayment}>
                결제하기
              </button>
              <ReleaseButton disabled={busy} onConfirm={release} />
            </div>
          </>
        )}

        {state === "PAYING" && (
          <>
            <h2>
              <span className="badge badge-warn">결제 중</span> 카드 인증을 진행하세요
            </h2>
            <p className="muted">선점 만료까지 {holdLeft ?? "--:--"}</p>
            <Summary reservation={r} seats={seatViews} />
            {pay === null ? (
              <button className="btn btn-primary" type="button" disabled={busy} onClick={startPayment}>
                결제 계속하기
              </button>
            ) : config?.pg === "toss" ? (
              <button className="btn btn-primary" type="button" disabled={busy} onClick={payWithToss}>
                토스로 {formatWon(pay.amount)} 결제
              </button>
            ) : (
              <FakePgPanel amount={pay.amount} disabled={busy} onPay={payWithFake} />
            )}
          </>
        )}

        {state === "CONFIRMING" && (
          <>
            <h2>
              <span className="badge badge-warn">승인 확인 중</span> 결제사 응답을 기다리고 있습니다
            </h2>
            <p className="muted">
              {CONFIRMING_POLL_MS / 1000}초마다 다시 확인합니다. 응답이 늦으면 서버가 결제사에 직접 조회해 정리합니다(최대 5분).
            </p>
            <Summary reservation={r} seats={seatViews} />
          </>
        )}

        {state === "CONFIRMED" && (
          <>
            <h2>
              <span className="badge badge-ok">예매 완료</span> 결제가 승인되었습니다
            </h2>
            <dl className="kv">
              <dt>승인 시각</dt>
              <dd>{formatKst(r.payment?.approvedAt)}</dd>
              <dt>결제 금액</dt>
              <dd>{formatWon(r.payment?.amount ?? r.totalAmount)}</dd>
            </dl>
            <Summary reservation={r} seats={seatViews} />
            <CancelButton disabled={busy} onConfirm={cancel} />
          </>
        )}

        {state === "FAILED" && (
          <>
            <h2>
              <span className="badge badge-danger">결제 실패</span> 결제가 승인되지 않았습니다
            </h2>
            <dl className="kv">
              <dt>실패 사유</dt>
              <dd className="mono">{r.payment?.failureCode ?? "-"}</dd>
              <dt>시도</dt>
              <dd>{r.payment?.attempt ?? 0}회</dd>
            </dl>
            <p className="muted">좌석 선점은 유지됩니다. 만료까지 {holdLeft ?? "--:--"}</p>
            <Summary reservation={r} seats={seatViews} />
            <div className="row">
              <button className="btn btn-primary" type="button" disabled={busy || holdOver || r.status !== "HOLDING"} onClick={startPayment}>
                다시 결제
              </button>
              {r.status === "HOLDING" && <ReleaseButton disabled={busy} onConfirm={release} />}
            </div>
          </>
        )}

        {state === "EXPIRED" && (
          <>
            <h2>
              <span className="badge badge-danger">만료</span> 선점 시간이 지났습니다
            </h2>
            <p className="muted">좌석이 다시 판매로 풀렸습니다. 대기열로 돌아가 다시 시도해 주세요.</p>
            <Link className="btn btn-primary" href={queueHref}>
              대기열로 가기
            </Link>
          </>
        )}

        {state === "CANCELLED" && (
          <>
            <h2>
              <span className="badge">취소됨</span> 예약이 취소되었습니다
            </h2>
            <p className="muted">결제 금액은 전액 환불됩니다.</p>
            <Link className="btn" href="/">
              회차 목록으로
            </Link>
          </>
        )}
      </section>
    </main>
  );
}

type TossReturn =
  | { kind: "success"; body: { orderId: string; paymentKey: string; amount: number } }
  | { kind: "failed"; message: string };

function readTossReturn(search: string): TossReturn | null {
  const q = new URLSearchParams(search);
  if (q.has("failed")) {
    const code = q.get("code");
    return { kind: "failed", message: `${q.get("message") ?? ""}${code ? ` (${code})` : ""}`.trim() };
  }
  const paymentKey = q.get("paymentKey");
  const orderId = q.get("orderId");
  if (!paymentKey || !orderId) return null;
  return { kind: "success", body: { orderId, paymentKey, amount: Number(q.get("amount")) } };
}

function Summary({ reservation, seats }: { reservation: ReservationView; seats: Map<number, SeatView> }) {
  const labels = useMemo(
    () =>
      reservation.seatIds.map((id) => {
        const s = seats.get(id);
        return s ? `${seatLabel(s)} · ${s.grade} · ${formatWon(s.price)}` : `좌석 #${id}`;
      }),
    [reservation.seatIds, seats],
  );
  return (
    <dl className="kv">
      <dt>좌석</dt>
      <dd>
        {labels.map((l) => (
          <div key={l}>{l}</div>
        ))}
      </dd>
      <dt>합계</dt>
      <dd>
        <strong>{formatWon(reservation.totalAmount)}</strong>
      </dd>
      <dt>선점 만료</dt>
      <dd>{formatKst(reservation.expiresAt)}</dd>
    </dl>
  );
}

function FakePgPanel({ amount, disabled, onPay }: { amount: number; disabled: boolean; onPay: (fault: FakeFault) => void }) {
  const [fault, setFault] = useState<FakeFault>("NONE");
  return (
    <div className="notice">
      <h3>카드 결제 (Fake PG)</h3>
      <p className="muted">테스트용 결제사입니다. 실제 카드가 청구되지 않습니다.</p>
      <details style={{ marginBottom: "var(--space-3)" }}>
        <summary>개발자: 고장 주입</summary>
        <label className="row" style={{ marginTop: "var(--space-2)" }}>
          fault
          <select value={fault} onChange={(e) => setFault(e.target.value as FakeFault)}>
            {FAKE_FAULTS.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </label>
      </details>
      <button className="btn btn-primary" type="button" disabled={disabled} onClick={() => onPay(fault)}>
        {formatWon(amount)} 결제 (Fake PG)
      </button>
    </div>
  );
}

/** Two-step inline confirm instead of window.confirm. */
function ConfirmButton({
  label,
  confirmLabel,
  disabled,
  onConfirm,
}: {
  label: string;
  confirmLabel: string;
  disabled: boolean;
  onConfirm: () => void;
}) {
  const [armed, setArmed] = useState(false);
  if (!armed) {
    return (
      <button className="btn btn-danger" type="button" disabled={disabled} onClick={() => setArmed(true)}>
        {label}
      </button>
    );
  }
  return (
    <span className="row">
      <button className="btn btn-danger" type="button" disabled={disabled} onClick={onConfirm}>
        {confirmLabel}
      </button>
      <button className="btn" type="button" onClick={() => setArmed(false)}>
        그만두기
      </button>
    </span>
  );
}

function CancelButton({ disabled, onConfirm }: { disabled: boolean; onConfirm: () => void }) {
  return <ConfirmButton label="전액 취소" confirmLabel="정말 전액 취소" disabled={disabled} onConfirm={onConfirm} />;
}

function ReleaseButton({ disabled, onConfirm }: { disabled: boolean; onConfirm: () => void }) {
  return <ConfirmButton label="선점 취소 · 다른 좌석 선택" confirmLabel="선점 취소 확인" disabled={disabled} onConfirm={onConfirm} />;
}
