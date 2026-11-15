"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import { getMemberId } from "./member";

/** Current time, re-rendered every [intervalMs]. 0 until mounted so server and first client render match. */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(0);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    const first = window.setTimeout(tick, 0);
    const id = window.setInterval(tick, intervalMs);
    return () => {
      window.clearTimeout(first);
      window.clearInterval(id);
    };
  }, [intervalMs]);
  return now;
}

const noSubscription = () => () => {};

/** This browser's 간이 회원 id (localStorage); null during server render and hydration. */
export function useMemberId(): number | null {
  return useSyncExternalStore(noSubscription, getMemberId, () => null);
}
