"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { MemberChip } from "@/components/MemberChip";
import { api, type ApiResult, type Showtime } from "@/lib/api";
import { formatKst } from "@/lib/format";
import styles from "./page.module.css";

/** Home: public showtimes, each linking to its 대기열 (ADR-0008 §1). */
export default function HomePage() {
  const [result, setResult] = useState<ApiResult<Showtime[]> | null>(null);

  useEffect(() => {
    let alive = true;
    api.showtimes().then((r) => alive && setResult(r));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <main className="container">
      <MemberChip />
      <h1>오픈 예정 회차</h1>
      {result === null && <p className="muted">불러오는 중…</p>}
      {result && !result.ok && <div className="notice notice-danger">회차 목록을 불러오지 못했습니다 ({result.code}).</div>}
      {result?.ok && result.body.length === 0 && <p className="muted">열려 있거나 예정된 회차가 없습니다.</p>}
      {result?.ok && (
        <ul className={styles.list}>
          {result.body.map((s) => (
            <li key={s.showtimeId} className="card">
              <h2>{s.showTitle}</h2>
              <dl className="kv">
                <dt>공연장</dt>
                <dd>{s.venueName}</dd>
                <dt>공연</dt>
                <dd>{formatKst(s.startsAt)}</dd>
                <dt>예매 오픈</dt>
                <dd>{formatKst(s.openAt)}</dd>
                <dt>좌석</dt>
                <dd>{s.totalSeats.toLocaleString("ko-KR")}석</dd>
              </dl>
              <Link className="btn btn-primary" href={`/showtimes/${s.showtimeId}/queue`}>
                대기열로 가기
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
