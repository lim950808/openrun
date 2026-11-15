"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { MemberChip } from "@/components/MemberChip";
import { api, type Showtime } from "@/lib/api";
import { countdown } from "@/lib/countdown";
import { formatKst } from "@/lib/format";
import { useMemberId, useNow } from "@/lib/hooks";
import { clearVisitorToken, getAccessToken, getVisitorToken, setAccessToken, setVisitorToken, subscribeTokens } from "@/lib/tokens";

/** requirements §1 "클라이언트 2~3초". */
const POLL_MS = 2500;

type Phase =
  | { kind: "loading" }
  | { kind: "notFound" }
  | { kind: "ready" }
  | { kind: "notOpen" }
  | { kind: "entering" }
  | { kind: "waiting"; token: string; ahead: number | null; enteredAt: string | null }
  | { kind: "admitted" }
  | { kind: "out"; reason: string }
  | { kind: "error"; code: string };

/** 대기열 (ADR-0008 §1 row 1, M4-1): 오픈 전 · 대기 · 입장 · 이탈. */
export default function QueuePage() {
  const { showtimeId } = useParams<{ showtimeId: string }>();
  const router = useRouter();
  const memberId = useMemberId();
  const now = useNow(1000);
  const [showtime, setShowtime] = useState<Showtime | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: "loading" });
  const hasAccess = useSyncExternalStore(
    subscribeTokens,
    () => getAccessToken(showtimeId) !== null,
    () => false,
  );

  const admit = useCallback(
    (accessToken: string, expiresAt: string) => {
      setAccessToken(showtimeId, accessToken, expiresAt);
      clearVisitorToken(showtimeId);
      setPhase({ kind: "admitted" });
      router.replace(`/showtimes/${showtimeId}/seats`);
    },
    [router, showtimeId],
  );

  useEffect(() => {
    let alive = true;
    api.showtime(showtimeId).then((r) => {
      if (!alive) return;
      if (!r.ok) {
        setPhase(r.status === 404 ? { kind: "notFound" } : { kind: "error", code: r.code });
        return;
      }
      setShowtime(r.body);
      const visitor = getVisitorToken(showtimeId);
      if (visitor) setPhase({ kind: "waiting", token: visitor.token, ahead: null, enteredAt: visitor.enteredAt });
      else setPhase(Date.parse(r.body.entryOpensAt) > Date.now() ? { kind: "notOpen" } : { kind: "ready" });
    });
    return () => {
      alive = false;
    };
  }, [showtimeId]);

  const waitingToken = phase.kind === "waiting" ? phase.token : null;
  useEffect(() => {
    if (!waitingToken) return;
    let alive = true;
    const poll = async () => {
      const r = await api.queueMe(showtimeId, waitingToken);
      if (!alive) return;
      if (r.ok) {
        if (r.body.status === "ADMITTED") admit(r.body.accessToken, r.body.accessTokenExpiresAt);
        else if (r.body.status === "WAITING") {
          const ahead = r.body.ahead;
          setPhase((p) => (p.kind === "waiting" ? { ...p, ahead } : p));
        } else {
          clearVisitorToken(showtimeId);
          setPhase({ kind: "out", reason: "NOT_IN_QUEUE" });
        }
      } else if (r.status === 401) {
        clearVisitorToken(showtimeId);
        setPhase({ kind: "out", reason: r.code });
      }
      // Other failures (network, 5xx): keep polling; the next tick retries.
    };
    poll();
    const id = window.setInterval(poll, POLL_MS);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [waitingToken, showtimeId, admit]);

  const enter = async () => {
    if (memberId === null) return;
    setPhase({ kind: "entering" });
    const r = await api.enterQueue(showtimeId, memberId);
    if (r.ok) {
      if (r.body.status === "ADMITTED") admit(r.body.accessToken, r.body.accessTokenExpiresAt);
      else {
        setVisitorToken(showtimeId, r.body.visitorToken, r.body.enteredAt);
        setPhase({ kind: "waiting", token: r.body.visitorToken, ahead: r.body.ahead, enteredAt: r.body.enteredAt });
      }
    } else if (r.code === "QUEUE_NOT_OPEN") setPhase({ kind: "notOpen" });
    else if (r.status === 404) setPhase({ kind: "notFound" });
    else setPhase({ kind: "error", code: r.code });
  };

  const entryOpen = showtime !== null && now !== 0 && Date.parse(showtime.entryOpensAt) <= now;

  return (
    <main className="container">
      <MemberChip />
      {showtime && (
        <section className="card">
          <h1>{showtime.showTitle} 대기열</h1>
          <dl className="kv">
            <dt>공연장</dt>
            <dd>{showtime.venueName}</dd>
            <dt>공연</dt>
            <dd>{formatKst(showtime.startsAt)}</dd>
            <dt>예매 오픈</dt>
            <dd>{formatKst(showtime.openAt)}</dd>
            <dt>대기열 진입</dt>
            <dd>{formatKst(showtime.entryOpensAt)}부터</dd>
          </dl>
        </section>
      )}

      {hasAccess && phase.kind !== "admitted" && (
        <div className="notice notice-ok">
          아직 유효한 입장 토큰이 있습니다. <Link href={`/showtimes/${showtimeId}/seats`}>좌석 선택으로 이동</Link>
        </div>
      )}

      <section className="card" aria-live="polite" data-state={phase.kind}>
        {phase.kind === "loading" && <p className="muted">불러오는 중…</p>}
        {phase.kind === "notFound" && (
          <>
            <h2>회차를 찾을 수 없습니다</h2>
            <Link href="/">회차 목록으로</Link>
          </>
        )}
        {phase.kind === "error" && (
          <>
            <h2>잠시 문제가 생겼습니다</h2>
            <p className="muted mono">{phase.code}</p>
            <button className="btn" type="button" onClick={() => window.location.reload()}>
              새로고침
            </button>
          </>
        )}
        {phase.kind === "notOpen" && showtime && (
          <>
            <h2>오픈 전</h2>
            <p>대기열 진입까지</p>
            <p className="big">{now === 0 ? "--:--" : countdown(showtime.entryOpensAt, now)}</p>
            <button className="btn btn-primary" type="button" disabled={!entryOpen || memberId === null} onClick={enter}>
              줄 서기
            </button>
          </>
        )}
        {phase.kind === "ready" && (
          <>
            <h2>지금 줄을 설 수 있습니다</h2>
            <p className="muted">입장 순서는 줄 선 순서(FIFO)입니다. 창을 닫거나 30초 넘게 응답이 없으면 줄에서 빠집니다.</p>
            <button className="btn btn-primary" type="button" disabled={memberId === null} onClick={enter}>
              줄 서기
            </button>
          </>
        )}
        {phase.kind === "entering" && <p className="muted">줄 서는 중…</p>}
        {phase.kind === "waiting" && (
          <>
            <h2>대기 중</h2>
            <p>내 앞에</p>
            <p className="big" data-testid="ahead">
              {phase.ahead === null ? "…" : `${phase.ahead.toLocaleString("ko-KR")}명`}
            </p>
            <p className="muted">
              진입 시각 {formatKst(phase.enteredAt)} · {POLL_MS / 1000}초마다 갱신
            </p>
            {showtime && now !== 0 && Date.parse(showtime.openAt) > now && (
              <p className="muted">입장은 예매 오픈({countdown(showtime.openAt, now)} 뒤)부터 시작됩니다.</p>
            )}
          </>
        )}
        {phase.kind === "admitted" && (
          <>
            <h2>입장했습니다</h2>
            <p>좌석 선택으로 이동합니다…</p>
          </>
        )}
        {phase.kind === "out" && (
          <>
            <h2>대기열에서 빠졌습니다</h2>
            <p className="muted">
              {phase.reason === "VISITOR_TOKEN_EXPIRED"
                ? "대기 토큰이 만료되었습니다(3시간)."
                : "응답이 30초 넘게 없어 순번이 정리되었거나 대기 정보가 없습니다."}
            </p>
            <button className="btn btn-primary" type="button" disabled={memberId === null} onClick={enter}>
              다시 줄 서기
            </button>
          </>
        )}
      </section>
    </main>
  );
}
