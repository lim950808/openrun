/** Display helpers. Instants are stored as ISO (UTC); shown in KST only. */

const kst = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  weekday: "short",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false,
});

export function formatKst(iso: string | null | undefined): string {
  if (!iso) return "-";
  return `${kst.format(new Date(iso))} KST`;
}

export function formatWon(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
