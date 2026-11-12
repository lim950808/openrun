#!/usr/bin/env python3
"""Summarize the latest M2 run in load/results into one markdown table (docs/metrics.md "M2 대기열" names).

Reads the newest m2-storm-*.json / m2-weekly-*.json (k6 handleSummary exports) and
m2-integrity-storm-*.json / m2-integrity-weekly-*.json (GET /api/admin/showtimes/{id}/queue-integrity).
Missing data prints n/a.

usage: load/report/m2_report.py [--results load/results] [--label TEXT] [--out path.md]
"""
import argparse
import glob
import json
import os
import sys


def latest(results, pattern):
    files = sorted(glob.glob(os.path.join(results, pattern)), key=os.path.getmtime)
    if not files:
        return {}
    with open(files[-1]) as f:
        return json.load(f)


def fmt(v, digits=1, pct=False):
    if v is None:
        return "n/a"
    if pct:
        return f"{v * 100:.{digits}f}%"
    if isinstance(v, float):
        return f"{v:,.{digits}f}"
    return f"{v:,}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default="load/results")
    ap.add_argument("--label", default="로컬, 참고용")
    ap.add_argument("--out")
    args = ap.parse_args()

    storm = latest(args.results, "m2-storm-*.json")
    weekly = latest(args.results, "m2-weekly-*.json")
    istorm = latest(args.results, "m2-integrity-storm-*.json")
    iweekly = latest(args.results, "m2-integrity-weekly-*.json")
    if not (storm or weekly or istorm or iweekly):
        print(f"no m2 results in {args.results}", file=sys.stderr)
        return 1

    rows = [
        ("rank_api_p99_ms", fmt(storm.get("rank_api_p99_ms")), fmt(weekly.get("rank_api_p99_ms")), "≤ 100"),
        ("rank_drift_count", fmt(storm.get("rank_drift_count")), fmt(weekly.get("rank_drift_count")), "0"),
        ("fifo_violation_rate", fmt(istorm.get("fifo_violation_rate"), 2, True), fmt(iweekly.get("fifo_violation_rate"), 2, True), "≤ 1% (구조상 0)"),
        ("abandon_rate", fmt(istorm.get("abandon_rate"), 1, True), fmt(iweekly.get("abandon_rate"), 1, True), "시나리오 15%와 비교"),
        ("admission_rate_actual", fmt(istorm.get("admission_rate_actual")), fmt(iweekly.get("admission_rate_actual")), "설정값 ±5%"),
        ("admission_delay_p50_s", fmt(istorm.get("admission_delay_p50_s")), fmt(iweekly.get("admission_delay_p50_s")), "보고만"),
        ("admission_delay_p99_s", fmt(istorm.get("admission_delay_p99_s")), fmt(iweekly.get("admission_delay_p99_s")), "보고만"),
        ("unauthorized_seat_calls", fmt(istorm.get("unauthorized_seat_calls")), fmt(iweekly.get("unauthorized_seat_calls")), "보고만"),
        ("entered / admitted / abandoned / waiting / lost",
         " / ".join(fmt(istorm.get(k)) for k in ("entered", "admitted", "abandoned", "waiting", "lost")),
         " / ".join(fmt(iweekly.get(k)) for k in ("entered", "admitted", "abandoned", "waiting", "lost")), "lost 0"),
        ("polls (rps)", f"{fmt(storm.get('polls'))} ({fmt(storm.get('poll_rps'))})", "n/a", "보고만"),
        ("hold_p99_ms", "n/a", fmt(weekly.get("hold_p99_ms")), "≤ 200"),
        ("hold success / seat_taken / unauthorized", "n/a",
         " / ".join(fmt(weekly.get(k)) for k in ("hold_success", "seat_taken", "unauthorized")), "보고만"),
        ("bot abandoned / re-entered", "n/a",
         " / ".join(fmt(weekly.get(k)) for k in ("queue_abandoned_by_bot", "queue_reentered")), "보고만"),
    ]
    lines = [
        f"M2 대기열 ({args.label})",
        "",
        f"- storm: {storm.get('vus', 'n/a')} VUs x {storm.get('duration_s', 'n/a')}s, poll {storm.get('poll_interval_s', 'n/a')}s, showtime {storm.get('showtime_id', 'n/a')}",
        f"- weekly: {weekly.get('members', 'n/a')} members over {weekly.get('duration_s', 'n/a')}s, "
        f"admissions {weekly.get('admissions_per_second', 'n/a')}/s, showtime {weekly.get('showtime_id', 'n/a')}",
        "",
        "| 지표 | 새로고침 폭풍 | 주간 오픈런 봇 | 목표 |",
        "|---|---|---|---|",
    ]
    lines += [f"| `{name}` | {a} | {b} | {target} |" if " " not in name else f"| {name} | {a} | {b} | {target} |" for name, a, b, target in rows]
    text = "\n".join(lines) + "\n"
    print(text)
    if args.out:
        with open(args.out, "w") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
