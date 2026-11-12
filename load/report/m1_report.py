#!/usr/bin/env python3
"""Summarize load/results/m1-*.json into the docs/reports/_template.md "전략 비교" table.

Reads arrival-rate runs (m1-<STRATEGY>-*.json), single-seat burst runs (m1-burst-<STRATEGY>-*.json) and hold
phase snapshots (m1-phases-<STRATEGY>-run<N>.json, from /api/admin/diagnostics/hold-phases after each arrival run).
Rules from docs/metrics.md: performance metrics (p99, throughput) are the median across runs with min~max;
count metrics (oversell_count, burst_oversell_occurred) are summed across runs; missing data prints n/a.

usage: load/report/m1_report.py [--results load/results] [--runner-spec TEXT] [--out path.md]
"""
import argparse
import glob
import json
import os
import statistics
import sys

STRATEGIES = ["NAIVE", "FOR_UPDATE", "SKIP_LOCKED", "CONDITIONAL_UPDATE", "REDIS_LUA"]


def values(runs, key):
    return [r[key] for r in runs if r.get(key) is not None]


def fmt_num(v, digits):
    return f"{v:,.{digits}f}"


def median_range(runs, key, digits=1):
    vs = values(runs, key)
    if not vs:
        return "n/a"
    med = fmt_num(statistics.median(vs), digits)
    return med if len(vs) == 1 else f"{med} ({fmt_num(min(vs), digits)}~{fmt_num(max(vs), digits)})"


def median_pct(runs, key):
    vs = values(runs, key)
    return "n/a" if not vs else f"{statistics.median(vs) * 100:.1f}%"


def summed(runs, key):
    vs = values(runs, key)
    if not vs:
        return "n/a"
    total = int(sum(vs))
    return str(total) if len(vs) == 1 else f"{total} (max {int(max(vs))}/run)"


def pairs_seats(runs):
    """Burst double-holds as `pairs (seats)` summed over runs; one target seat caps seats at 1 per run."""
    pairs = values(runs, "burst_oversell_pairs")
    seats = values(runs, "burst_oversell_occurred")
    if not pairs and not seats:
        return "n/a"
    p = str(int(sum(pairs))) if pairs else "n/a"
    s = str(int(sum(seats))) if seats else "n/a"
    return f"{p} ({s})"


def median_only(runs, key, digits=1):
    vs = values(runs, key)
    return "n/a" if not vs else fmt_num(statistics.median(vs), digits)


def phase_p99(snapshots, phase):
    """Median over runs of one phase's p99 from the diagnostics snapshots."""
    vs = [s["phases"][phase]["p99_ms"] for s in snapshots
          if s.get("phases", {}).get(phase, {}).get("p99_ms") is not None]
    return "n/a" if not vs else fmt_num(statistics.median(vs), 1)


PHASES = ["reserve_insert", "claim", "redis_script", "seats_insert", "total"]


def load_runs(pattern, exclude_prefix=None):
    by_strategy = {}
    for path in sorted(glob.glob(pattern)):
        if exclude_prefix and os.path.basename(path).startswith(exclude_prefix):
            continue
        with open(path, encoding="utf-8") as f:
            run = json.load(f)
        by_strategy.setdefault(run["strategy"], []).append(run)
    return by_strategy


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", default=os.path.join(os.path.dirname(__file__), "..", "results"))
    parser.add_argument("--runner-spec", help="text printed as a header line (fixed-environment rule)")
    parser.add_argument("--out", help="also write the Markdown to this path")
    args = parser.parse_args()

    arrival = load_runs(os.path.join(args.results, "m1-*.json"), exclude_prefix=("m1-burst-", "m1-phases-"))
    burst = load_runs(os.path.join(args.results, "m1-burst-*.json"))
    phases = load_runs(os.path.join(args.results, "m1-phases-*.json"))
    if not arrival and not burst:
        print(f"no m1-*.json under {args.results}", file=sys.stderr)
        return 1

    lines = []
    if args.runner_spec:
        lines.append(f"> runner: {args.runner_spec.strip()}")
    if arrival:
        a = next(iter(arrival.values()))[0]
        lines.append(
            f"> scenario: {a.get('scenario')} · peak {a.get('peak_rps')} rps · {a.get('duration_s'):g} s · "
            f"hold_ttl {a.get('hold_ttl') or 'n/a'} · hot_ratio {a.get('hot_ratio', 'n/a')} / "
            f"hot_pool {a.get('hot_pool', 'n/a')} · source_label: synthetic"
        )
    if burst:
        b = next(iter(burst.values()))[0]
        lines.append(
            f"> burst: {b.get('vus')} vus · {b.get('duration_s'):g} s · one seat, release on success · "
            "`burst_oversell_occurred` = overlapping reservation pairs (distinct seats)"
        )
    else:
        lines.append("> burst: n/a")
    lines.append("")
    lines.append(
        "| `strategy` | runs | `oversell_count` | `hold_p99_ms` (min~max) | `hold_throughput_rps` | `hold_attempt_rps` | "
        "`hold_success_rate` | `hold_conflict_rate` | `burst_oversell_occurred` | `burst_p99_ms` | "
        "`dropped_iterations` | `planned_attempt_rps` |"
    )
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    present = set(arrival) | set(burst)
    ordered = [s for s in STRATEGIES if s in present] + sorted(present - set(STRATEGIES))
    for strategy in ordered:
        runs = arrival.get(strategy, [])
        bursts = burst.get(strategy, [])
        lines.append(
            f"| {strategy} | {len(runs)} | {summed(runs, 'oversell_count')} | {median_range(runs, 'hold_p99_ms')} | "
            f"{median_range(runs, 'hold_throughput_rps')} | {median_range(runs, 'hold_attempt_rps')} | "
            f"{median_pct(runs, 'hold_success_rate')} | {median_pct(runs, 'hold_conflict_rate')} | "
            f"{pairs_seats(bursts)} | {median_only(bursts, 'burst_p99_ms')} | "
            f"{summed(runs, 'dropped_iterations')} | {median_only(runs, 'planned_attempt_rps')} |"
        )
    if phases:
        lines.append("")
        lines.append("hold 단계별 p99 (ms, 3회 중앙값)")
        lines.append("")
        lines.append(
            "| `strategy` | " + " | ".join(PHASES) + " | `hikari_acquire_p99_ms` | `hikari_pending_max` |"
        )
        lines.append("|---" * (len(PHASES) + 3) + "|")
        phase_ordered = [s for s in STRATEGIES if s in phases] + sorted(set(phases) - set(STRATEGIES))
        for strategy in phase_ordered:
            snaps = phases[strategy]
            lines.append(
                f"| {strategy} | " + " | ".join(phase_p99(snaps, p) for p in PHASES) + " | "
                f"{median_only(snaps, 'hikari_acquire_p99_ms')} | {median_only(snaps, 'hikari_pending_max', 0)} |"
            )
    text = "\n".join(lines) + "\n"
    print(text, end="")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
