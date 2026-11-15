"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from "react";
import { SeatMap } from "@/components/SeatMap";
import { api, type SeatView } from "@/lib/api";
import { countdown } from "@/lib/countdown";
import { formatWon } from "@/lib/format";
import { useNow } from "@/lib/hooks";
import { getLastReservationId, setLastReservationId } from "@/lib/member";
import { seatLabel } from "@/lib/seatmap";
import { accessTokenSnapshot, clearAccessToken, subscribeTokens } from "@/lib/tokens";

const MAX_SEATS = 2;
const REFRESH_MS = 10_000;

type Notice = { tone: "warn" | "danger"; text: string; link?: { href: string; label: string } };

/** 좌석맵 (ADR-0008 §1 row 2, M4-2). Needs the access token from the queue. */
export default function SeatsPage() {
  const { showtimeId } = useParams<{ showtimeId: string }>();
  const router = useRouter();
  const now = useNow(1000);
  const access = useSyncExternalStore(
    subscribeTokens,
    () => accessTokenSnapshot(showtimeId),
    () => undefined,
  );
  const [expired, setExpired] = useState(false);
  const [seats, setSeats] = useState<SeatView[] | null>(null);
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set());
  const [notice, setNotice] = useState<Notice | null>(null);
  const [holding, setHolding] = useState(false);

  const tokenExpired = useCallback(() => {
    clearAccessToken(showtimeId);
    setExpired(true);
  }, [showtimeId]);

  const token = access?.token ?? null;
  const load = useCallback(() => {
    if (!token) return Promise.resolve();
    return api.seats(showtimeId, token).then((r) => {
      if (r.ok) {
        setSeats(r.body);
        const available = new Set(r.body.filter((s) => s.status === "AVAILABLE").map((s) => s.seatId));
        setSelected((prev) => {
          const kept = [...prev].filter((id) => available.has(id));
          return kept.length === prev.size ? prev : new Set(kept);
        });
      } else if (r.status === 401) tokenExpired();
    });
  }, [token, showtimeId, tokenExpired]);

  useEffect(() => {
    if (!token) return;
    const first = window.setTimeout(load, 0);
    const id = window.setInterval(load, REFRESH_MS);
    return () => {
      window.clearTimeout(first);
      window.clearInterval(id);
    };
  }, [token, load]);

  const toggle = useCallback((seat: SeatView) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(seat.seatId)) next.delete(seat.seatId);
      else if (next.size < MAX_SEATS) next.add(seat.seatId);
      else return prev;
      return next;
    });
  }, []);

  const chosen = useMemo(() => (seats ?? []).filter((s) => selected.has(s.seatId)), [seats, selected]);
  const total = chosen.reduce((sum, s) => sum + s.price, 0);

  const hold = async () => {
    if (!access || chosen.length === 0) return;
    setHolding(true);
    setNotice(null);
    const r = await api.hold(showtimeId, access.token, chosen.map((s) => s.seatId));
    if (r.ok) {
      setLastReservationId(r.body.reservationId);
      router.push(`/reservations/${r.body.reservationId}`);
      return;
    }
    setHolding(false);
    if (r.status === 401) tokenExpired();
    else if (r.code === "SEAT_TAKEN") {
      setSelected(new Set());
      setNotice({ tone: "warn", text: "방금 다른 분이 먼저 선점한 좌석이 있습니다. 좌석을 다시 골라 주세요." });
      load();
    } else if (r.code === "ACTIVE_RESERVATION_EXISTS") {
      const last = getLastReservationId();
      setNotice({
        tone: "warn",
        text: "이미 진행 중인 예약이 있습니다(1인 1건).",
        link: last ? { href: `/reservations/${last}`, label: "진행 중인 예약으로 이동" } : undefined,
      });
    } else if (r.code === "INVALID_SEAT_COUNT") {
      setNotice({ tone: "warn", text: `좌석은 1~${MAX_SEATS}석까지 고를 수 있습니다.` });
    } else setNotice({ tone: "danger", text: `선점하지 못했습니다 (${r.code}).` });
  };

  const queueHref = `/showtimes/${showtimeId}/queue`;

  if (access === undefined) return <main className="container muted">불러오는 중…</main>;

  const clockExpired = access && now !== 0 && Date.parse(access.expiresAt) <= now;
  if (access === null || expired || clockExpired) {
    return (
      <main className="container">
        <section className="card" data-state="no-access">
          <h1>{expired || clockExpired ? "입장 토큰이 만료되었습니다" : "입장 토큰이 없습니다"}</h1>
          <p className="muted">좌석 선택은 대기열을 통과한 뒤 10분 동안만 할 수 있습니다. 대기열 맨 뒤로 다시 들어가 주세요.</p>
          <Link className="btn btn-primary" href={queueHref}>
            대기열로 돌아가기
          </Link>
        </section>
      </main>
    );
  }

  return (
    <main className="container">
      <div className="row" style={{ justifyContent: "space-between", marginBottom: "var(--space-3)" }}>
        <h1 style={{ margin: 0 }}>좌석 선택</h1>
        <span className="badge badge-warn" data-testid="access-remaining">
          입장 토큰 {now === 0 ? "--:--" : countdown(access.expiresAt, now)} 남음
        </span>
      </div>

      {notice && (
        <div className={`notice notice-${notice.tone}`} role="status">
          {notice.text} {notice.link && <Link href={notice.link.href}>{notice.link.label}</Link>}
        </div>
      )}

      {seats === null ? <p className="muted">좌석을 불러오는 중…</p> : <SeatMap seats={seats} selected={selected} onToggle={toggle} />}

      <section className="card">
        <h2>선택한 좌석 ({chosen.length}/{MAX_SEATS})</h2>
        {chosen.length === 0 ? (
          <p className="muted">좌석맵에서 최대 {MAX_SEATS}석을 고르세요. 좌석 상태는 10초마다 갱신됩니다.</p>
        ) : (
          <ul>
            {chosen.map((s) => (
              <li key={s.seatId}>
                {seatLabel(s)} · {s.grade} · {formatWon(s.price)}
              </li>
            ))}
          </ul>
        )}
        <p>
          합계 <strong>{formatWon(total)}</strong>
        </p>
        <button className="btn btn-primary" type="button" disabled={chosen.length === 0 || holding} onClick={hold}>
          {holding ? "선점 중…" : "선점하기"}
        </button>
      </section>
    </main>
  );
}
