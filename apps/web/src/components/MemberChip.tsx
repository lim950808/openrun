"use client";

import { useState, useSyncExternalStore } from "react";
import { useMemberId } from "@/lib/hooks";
import { getNickname, setNickname, subscribeNickname } from "@/lib/member";
import styles from "./MemberChip.module.css";

/** 간이 회원: shows the issued id and an editable display nickname. */
export function MemberChip() {
  const memberId = useMemberId();
  const nickname = useSyncExternalStore(subscribeNickname, getNickname, () => "");
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");

  if (memberId === null) return <div className={styles.chip}>회원 확인 중…</div>;

  if (editing) {
    return (
      <form
        className={styles.chip}
        onSubmit={(e) => {
          e.preventDefault();
          setNickname(draft);
          setEditing(false);
        }}
      >
        <label className={styles.label}>
          닉네임
          <input className={styles.input} value={draft} maxLength={20} onChange={(e) => setDraft(e.target.value)} autoFocus />
        </label>
        <button className="btn" type="submit">
          저장
        </button>
        <button className="btn" type="button" onClick={() => setEditing(false)}>
          닫기
        </button>
      </form>
    );
  }

  return (
    <div className={styles.chip}>
      <span>
        <strong>{nickname || "손님"}</strong> <span className="muted mono">#{memberId}</span>
      </span>
      <button
        className="btn"
        type="button"
        onClick={() => {
          setDraft(nickname);
          setEditing(true);
        }}
      >
        닉네임 변경
      </button>
    </div>
  );
}
