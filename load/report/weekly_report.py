#!/usr/bin/env python3
"""Fill docs/reports/_template.md for one Saturday: docs/reports/<date>.md + docs/reports/<date>.json (ADR-0008 §3).

Inputs (all optional; a missing input prints n/a and, for the benches, a note under the header):
  --weekly     load/weekly-openrun.sh results: newest weekly-run-*.json and the files with its stamp
               (weekly-integrity · weekly-queue-integrity · weekly-payment-integrity · weekly-illegal-transition ·
               weekly-ticks),
               plus its k6 summary m2-weekly-*.json
  --bench-m1   bench-m1-results artifact: m1-<STRATEGY>-*.json, m1-burst-<STRATEGY>-*.json (load/report/m1_report.py)
  --bench-m2   bench-m2-results artifact: m2-storm-*, m2-weekly-*, m2-integrity-storm-*, m2-integrity-weekly-*,
               m2-ticks-weekly-*, m3-payment-integrity-weekly-*.json (load/report/m2_report.py)
Rules (docs/metrics.md): performance = bench median (min~max), counts = bench sum and the weekly open run apart,
zero denominators print n/a. Narrative sections stay `> 직접 작성`; no number here is anything but a copied input.

usage: weekly_report.py --date YYYY-MM-DD [--weekly load/results] [--bench-m1 DIR] [--bench-m2 DIR]
                        [--reports docs/reports] [--runner-spec FILE] [--label TEXT] [--bench-note TEXT]
"""
import argparse
import datetime
import glob
import json
import os
import re
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from m1_report import STRATEGIES, load_runs, values  # noqa: E402
from m2_report import fmt, latest, tick_p99  # noqa: E402

NA = "n/a"
KST = datetime.timezone(datetime.timedelta(hours=9))
WEEKDAYS = "월화수목금토일"
DEFAULT_STRATEGY = "CONDITIONAL_UPDATE"  # apps/api application.yml openrun.hold.strategy default
TO_WRITE = "> 직접 작성"


def read_json(path):
    if not path or not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def kst(iso):
    if not iso:
        return NA
    at = datetime.datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return at.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S KST")


def scenario_value(path, key):
    """Top-level `key: number` from the scenario yaml (no PyYAML dependency on the runner)."""
    try:
        with open(path, encoding="utf-8") as f:
            m = re.search(rf"^{re.escape(key)}:\s*([0-9.]+)", f.read(), re.M)
        return float(m.group(1)) if m else None
    except OSError:
        return None


def stats(runs, key):
    vs = values(runs, key)
    if not vs:
        return None, None, None, 0
    return statistics.median(vs), min(vs), max(vs), len(vs)


def med_text(med, lo, hi, n, digits=1):
    if med is None:
        return NA
    text = f"{med:,.{digits}f}"
    return text if n == 1 else f"{text} ({lo:,.{digits}f}~{hi:,.{digits}f})"


def total(*parts):
    """Sum of counts; None when every part is missing."""
    present = [p for p in parts if p is not None]
    return int(sum(present)) if present else None


def ratio(num, den):
    return None if num is None or not den else num / den


def pp(rate, expected):
    return None if rate is None or expected is None else round((rate - expected) * 100, 2)


def load_weekly(d):
    if not d:
        return {}
    metas = sorted(glob.glob(os.path.join(d, "weekly-run-*.json")))
    if not metas:
        return {}
    meta = read_json(metas[-1])
    stamp = meta.get("stamp") or os.path.basename(metas[-1])[len("weekly-run-"):-len(".json")]
    k6 = read_json(os.path.join(d, meta["k6_summary"])) if meta.get("k6_summary") else latest(d, "m2-weekly-*.json")
    illegal = read_json(os.path.join(d, f"weekly-illegal-transition-{stamp}.json"))
    count = next((m.get("value") for m in illegal.get("measurements", []) if m.get("statistic") == "COUNT"), None)
    return {
        "meta": meta,
        "k6": k6,
        "integrity": read_json(os.path.join(d, f"weekly-integrity-{stamp}.json")),
        "queue": read_json(os.path.join(d, f"weekly-queue-integrity-{stamp}.json")),
        "payment": read_json(os.path.join(d, f"weekly-payment-integrity-{stamp}.json")),
        "ticks": read_json(os.path.join(d, f"weekly-ticks-{stamp}.json")),
        "illegal_transition_count": int(count) if count is not None else None,
    }


def runner_text(label, path):
    if not path or not os.path.exists(path):
        return f"{label} · 사양 파일 없음"
    with open(path, encoding="utf-8") as f:
        raw = f.read()
    nproc = re.search(r"nproc=(\d+)", raw)
    mem = re.search(r"^Mem:\s+(\d+)", raw, re.M)
    kernel = re.search(r"kernel=(\S+)", raw)
    if not (nproc and mem and kernel):
        return f"{label} · {' '.join(raw.split())}"
    return f"{label} · {nproc.group(1)} vCPU · {mem.group(1)}GB · kernel {kernel.group(1)}"


def previous_report(reports, date):
    prev = sorted(p for p in glob.glob(os.path.join(reports, "????-??-??.json"))
                  if os.path.basename(p)[:10] < date)
    return read_json(prev[-1]) if prev else {}


def count_reports(reports, date):
    names = [os.path.basename(p) for p in glob.glob(os.path.join(reports, "*.md"))]
    return len([n for n in names if n not in ("_template.md", "README.md", f"{date}.md")])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--date", required=True, help="run date (KST), YYYY-MM-DD")
    ap.add_argument("--weekly", default="load/results")
    ap.add_argument("--bench-m1")
    ap.add_argument("--bench-m2")
    ap.add_argument("--reports", default="docs/reports")
    ap.add_argument("--runner-spec", help="runner-spec.txt; default <weekly>/runner-spec.txt")
    ap.add_argument("--label", help="runner label; default GitHub Actions ubuntu-latest on Actions, else 로컬, 참고용")
    ap.add_argument("--bench-note", default="", help="why bench artifacts are missing (header note)")
    args = ap.parse_args()

    date = datetime.date.fromisoformat(args.date)
    label = args.label or ("GitHub Actions ubuntu-latest" if os.environ.get("GITHUB_ACTIONS") == "true" else "로컬, 참고용")
    runner_spec = runner_text(label, args.runner_spec or os.path.join(args.weekly, "runner-spec.txt"))

    w = load_weekly(args.weekly)
    meta, k6 = w.get("meta", {}), w.get("k6", {})
    wi, wq, wp = w.get("integrity", {}), w.get("queue", {}), w.get("payment", {})
    wt = w.get("ticks", {})

    m1_dir = args.bench_m1 if args.bench_m1 and os.path.isdir(args.bench_m1) else None
    arrival = load_runs(os.path.join(m1_dir, "m1-*.json"), exclude_prefix=("m1-burst-", "m1-phases-")) if m1_dir else {}
    burst = load_runs(os.path.join(m1_dir, "m1-burst-*.json")) if m1_dir else {}
    m2_dir = args.bench_m2 if args.bench_m2 and os.path.isdir(args.bench_m2) else None
    storm = latest(m2_dir, "m2-storm-*.json") if m2_dir else {}
    b2k6 = latest(m2_dir, "m2-weekly-*.json") if m2_dir else {}
    b2q = latest(m2_dir, "m2-integrity-weekly-*.json") if m2_dir else {}
    b2p = latest(m2_dir, "m3-payment-integrity-weekly-*.json") if m2_dir else {}
    b2t = latest(m2_dir, "m2-ticks-weekly-*.json") if m2_dir else {}

    strategy_env = os.environ.get("OPENRUN_HOLD_STRATEGY")
    strategy = strategy_env or DEFAULT_STRATEGY
    scenario_file = meta.get("scenario", "load/scenarios/weekly-default.yaml")
    abandon_p = scenario_value(scenario_file, "abandon_probability")
    pay_abandon_p = scenario_value(scenario_file, "payment_abandon_probability")
    admissions_cfg = meta.get("admissions_per_second") or k6.get("admissions_per_second")

    notes = []
    missing = [name for name, ok in (("bench-m1", arrival or burst), ("bench-m2", storm or b2q or b2p)) if not ok]
    if missing:
        notes.append(f"> 벤치 아티팩트 없음({', '.join(missing)}): {args.bench_note.strip() or '사유 미기록'}")
    if not meta:
        notes.append(f"> 주간 오픈런 결과 없음: {args.weekly}에 weekly-run-*.json이 없다")
    elif meta.get("open_at_adjusted"):
        notes.append(f"> 오픈 시각 조정: 준비가 예정 시각을 넘겨 openAt = {kst(meta.get('open_at'))}")

    out = {"run_date": args.date}

    # --- 머리말
    report_no = count_reports(args.reports, args.date) + 1
    m1_runs = len(arrival.get(strategy, []))
    runs_text = (
        f"벤치마크 bench-m1 {f'{m1_runs}회' if m1_runs else NA} 중앙값 (최소~최대) · "
        f"bench-m2 {'1회' if storm or b2q or b2p else NA}"
        f" + 주간 오픈런 1회 (showtime {meta.get('showtime_id', NA)}, open_at {kst(meta.get('open_at'))}, "
        f"k6 시작 {kst(meta.get('k6_started_at'))}, 봇 {fmt(meta.get('members'))}명)"
    )
    strategy_text = f"`{strategy}`" + ("" if strategy_env else " (application.yml 기본값)")
    out.update({
        "report_no": report_no,
        "app_commit": meta.get("app_commit"),
        "scenario_file": scenario_file,
        "scenario_hash": meta.get("scenario_hash"),
        "strategy": strategy,
        "runner_spec": runner_spec,
        "runs": runs_text,
        "source_label": "synthetic 100%",
        "showtime_id": meta.get("showtime_id"),
        "open_at": meta.get("open_at"),
        "open_at_adjusted": meta.get("open_at_adjusted"),
        "entry_opens_at": meta.get("entry_opens_at"),
        "k6_started_at": meta.get("k6_started_at"),
        "k6_finished_at": meta.get("k6_finished_at"),
        "members": meta.get("members"),
        "bench_note": notes[0] if missing else None,
    })

    # --- 정합성: bench oversell = non-NAIVE arrival oversell_count + burst burst_oversell_remaining, summed over runs.
    safe = [s for s in set(arrival) | set(burst) if s != "NAIVE"]
    oversell_bench = total(*[total(*values(arrival.get(s, []), "oversell_count"),
                                   *values(burst.get(s, []), "burst_oversell_remaining")) for s in safe])
    integ = {
        "oversell_count": (oversell_bench, wi.get("oversell_count")),
        "confirm_expire_race_remaining": (b2p.get("confirm_expire_race_remaining"), wp.get("confirm_expire_race_remaining")),
        "compensation_failed_count": (b2p.get("compensation_failed_count"), wp.get("compensation_failed_count")),
        "state_event_mismatch_count": (b2p.get("state_event_mismatch_count"), wp.get("state_event_mismatch_count")),
        "illegal_transition_count": (None, w.get("illegal_transition_count")),
    }
    for name, (b, wv) in integ.items():
        out[f"{name}_bench"] = b
        out[f"{name}_weekly"] = wv

    # --- 성능 (default strategy)
    runs = arrival.get(strategy, [])
    perf = {}
    for key in ("hold_p99_ms", "hold_throughput_rps"):
        med, lo, hi, n = stats(runs, key)
        perf[key] = (med, lo, hi, n)
        out.update({key: med, f"{key}_min": lo, f"{key}_max": hi, f"{key}_runs": n})
    out["rank_api_p99_ms"] = storm.get("rank_api_p99_ms")
    out["expire_backlog_max_s"] = wi.get("expire_backlog_max_s")
    prev = previous_report(args.reports, args.date)

    # --- 전략 비교
    strat_rows = []
    present = set(arrival) | set(burst)
    for s in [s for s in STRATEGIES if s in present] + sorted(present - set(STRATEGIES)) or STRATEGIES:
        a, b = arrival.get(s, []), burst.get(s, [])
        ov = total(*values(a, "oversell_count"), *values(b, "burst_oversell_remaining")) if (a or b) else None
        p99 = stats(a, "hold_p99_ms")
        thr = stats(a, "hold_throughput_rps")
        out[f"oversell_count_{s}"] = ov
        out[f"hold_p99_ms_{s}"] = p99[0]
        out[f"hold_throughput_rps_{s}"] = thr[0]
        strat_rows.append((s, fmt(ov), med_text(*p99), med_text(*thr)))
    out["oversell_count_naive_bench"] = out.get("oversell_count_NAIVE")

    # --- 대기열
    queue = {
        "abandon_rate": (wq.get("abandon_rate"), b2q.get("abandon_rate")),
        "fifo_violation_rate": (wq.get("fifo_violation_rate"), b2q.get("fifo_violation_rate")),
        "admission_delay_p50_s": (wq.get("admission_delay_p50_s"), b2q.get("admission_delay_p50_s")),
        "admission_delay_p99_s": (wq.get("admission_delay_p99_s"), b2q.get("admission_delay_p99_s")),
        "admission_tick_p99_ms": (tick_p99(wt), tick_p99(b2t)),
        "admission_rate_actual": (wq.get("admission_rate_actual"), b2q.get("admission_rate_actual")),
        "rank_drift_count": (k6.get("rank_drift_count"), storm.get("rank_drift_count")),
    }
    for name, (wv, b) in queue.items():
        out[f"{name}_weekly"] = wv
        out[f"{name}_bench"] = b
    out["admission_tick_p99_ms"] = queue["admission_tick_p99_ms"][0]

    # --- 결제
    faults = k6.get("faults_injected") or {}
    pay = {
        "hold_to_confirm_rate": (wp.get("hold_to_confirm_rate"), b2p.get("hold_to_confirm_rate")),
        "confirm_expire_race_occurred": (wp.get("confirm_expire_race_occurred"), b2p.get("confirm_expire_race_occurred")),
        "unknown_count": (wp.get("unknown_count"), b2p.get("unknown_count")),
        "unknown_dwell_p99_s": (wp.get("unknown_dwell_p99_s"), b2p.get("unknown_dwell_p99_s")),
        "webhook_ack_p99_ms": (wp.get("webhook_ack_p99_ms"), b2p.get("webhook_ack_p99_ms")),
        "webhook_stale_count": (wp.get("webhook_stale_count"), b2p.get("webhook_stale_count")),
        "webhook_duplicate_count": (wp.get("webhook_duplicate_count"), b2p.get("webhook_duplicate_count")),
    }
    for name, (wv, b) in pay.items():
        out[f"{name}_weekly"] = wv
        out[f"{name}_bench"] = b
    for f_name, n in faults.items():
        out[f"faults_injected_{f_name}"] = n

    # --- 퍼널 (k6 member counts; app counts where the queue-integrity has them)
    stages = [
        ("enqueued", k6.get("queue_entered"), wq.get("entered")),
        ("admitted", k6.get("queue_admitted"), wq.get("admitted")),
        ("held", k6.get("hold_success"), None),
        ("paying", k6.get("payment_started"), None),
        ("confirmed", k6.get("confirm_ok"), None),
    ]
    enq = stages[0][1]
    funnel_rows = []
    prev_n = None
    for name, n, app in stages:
        share = ratio(n, enq)
        out[f"funnel_{name}"] = n
        out[f"funnel_share_{name}"] = share
        if name != "enqueued":
            out[f"funnel_rate_{name}"] = ratio(n, prev_n)
        count_text = fmt(n) + (f" (app {fmt(app)})" if app is not None else "")
        funnel_rows.append((name, count_text, fmt(share, 1, True)))
        prev_n = n
    rec = {
        "abandon": pp(wq.get("abandon_rate"), abandon_p),
        "payment_abandon": pp(ratio(k6.get("payment_abandoned"), k6.get("hold_success")), pay_abandon_p),
        "macro": None,
    }
    for name, v in rec.items():
        out[f"recovery_error_pp_{name}"] = v

    # --- 한 줄 요약 (machine sentence)
    core = ["oversell_count", "confirm_expire_race_remaining", "compensation_failed_count", "state_event_mismatch_count"]
    nonzero, unavailable = [], []
    sources = set()
    for name in core:
        b, wv = integ[name]
        if b is not None:
            sources.add("벤치")
        if wv is not None:
            sources.add("주간 오픈런")
        if b is None and wv is None:
            unavailable.append(name)
        elif (b or 0) > 0 or (wv or 0) > 0:
            nonzero.append(f"{name} {fmt(total(b, wv))}")
    scope = " + ".join(x for x in ("벤치", "주간 오픈런") if x in sources) or NA
    if nonzero:
        integrity_text = "정합성 위반: " + ", ".join(nonzero)
    elif unavailable:
        integrity_text = f"정합성 {len(core) - len(unavailable)}지표 0 ({scope}; n/a: {', '.join(unavailable)})"
    else:
        integrity_text = f"정합성 4지표 모두 0 ({scope})"
    hold_med = perf["hold_p99_ms"][0]
    summary = (
        f"{integrity_text}, `hold_p99_ms` 중앙값 {fmt(hold_med)} ms (목표 ≤ 200), "
        f"주간 오픈런 `admission_rate_actual` {fmt(wq.get('admission_rate_actual'))}/s (설정 {fmt(admissions_cfg)}/s)."
    )
    out["summary_sentence"] = summary

    # --- Markdown
    def pv(key):
        return fmt(prev.get(key)) if prev else NA

    def wb(wv, b, digits=1, pct=False):
        """Weekly value with the bench-m2 value in parentheses."""
        return f"{fmt(wv, digits, pct)} (벤치 {fmt(b, digits, pct)})"

    injected = ", ".join(f"{k} {fmt(v)}" for k, v in faults.items()) or NA
    expected_htc = f"{(1 - pay_abandon_p) * 100:.1f}%" if pay_abandon_p is not None else NA
    lines = [
        f"# 주간 오픈런 리포트 #{report_no} — {args.date} ({WEEKDAYS[date.weekday()]})",
        "",
        "<!-- load/report/weekly_report.py가 생성. 숫자는 러너 결과에서 옮긴 것만, 서술은 `> 직접 작성` 자리에 사람이 쓴다. -->",
        "",
        "## 머리말",
        "",
        *([*notes, ""] if notes else []),
        "| 차원 | 값 |",
        "|---|---|",
        f"| `report_no` / `run_date` | #{report_no} / {args.date} |",
        f"| `app_commit` | `{meta.get('app_commit') or NA}` |",
        f"| `scenario_file` / `scenario_hash` | `{scenario_file}` / `{meta.get('scenario_hash') or NA}` |",
        f"| `strategy` | {strategy_text} |",
        f"| `runner_spec` | {runner_spec} |",
        f"| `runs` | {runs_text} |",
        "| `source_label` | synthetic 100% |",
        f"| 이번 주 변경 | {TO_WRITE} |",
        "",
        "## 한 줄 요약",
        "",
        summary,
        "",
        f"{TO_WRITE}: 이번 주에 무엇이 달라졌는가",
        "",
        "## 정합성 (0이어야 하는 것)",
        "",
        "| 지표 | 벤치마크 3회 합계 | 주간 오픈런 | 목표 |",
        "|---|---|---|---|",
    ]
    targets = {"illegal_transition_count": "보고"}
    for name, (b, wv) in integ.items():
        lines.append(f"| `{name}` | {fmt(b)} | {fmt(wv)} | {targets.get(name, '0')} |")
    lines += [
        "",
        "- `oversell_count` 벤치 값은 bench-m1의 NAIVE를 뺀 전략 4종 × 3회의 도착률 실행 `oversell_count`와 버스트 "
        "`burst_oversell_remaining` 합. NAIVE는 재현용이라 아래 전략 비교 표에 따로 적는다.",
        "- 결제 3지표의 벤치 값은 bench-m2 주간 봇 1회(`m3-payment-integrity-weekly`). "
        "`illegal_transition_count`는 벤치 아티팩트에 없어 주간 오픈런만(`openrun.api.errors{code=ILLEGAL_TRANSITION}`, "
        "프로세스 전역, 카운터 미등록이면 0).",
        "",
        "## 성능 (벤치마크 중앙값)",
        "",
        "| 지표 | 값 (최소~최대) | 지난주 | 목표 |",
        "|---|---|---|---|",
        f"| `hold_p99_ms` | {med_text(*perf['hold_p99_ms'])} | {pv('hold_p99_ms')} | ≤ 200 |",
        f"| `hold_throughput_rps` | {med_text(*perf['hold_throughput_rps'])} | {pv('hold_throughput_rps')} | 보고 |",
        f"| `rank_api_p99_ms` | {fmt(storm.get('rank_api_p99_ms'))} | {pv('rank_api_p99_ms')} | ≤ 100 |",
        f"| `expire_lag_s` (p99) | {fmt(wi.get('expire_backlog_max_s'))} ¹ | {pv('expire_backlog_max_s')} | ≤ 60 |",
        "",
        f"- `hold_p99_ms` · `hold_throughput_rps`는 bench-m1 `{strategy}` 도착률 실행, `rank_api_p99_ms`는 bench-m2 "
        "새로고침 폭풍 1회.",
        "- ¹ 주간 오픈런 integrity의 `expire_backlog_max_s`: 점검 시점 최대값(백로그), p99 미측정.",
        "",
        "### 전략 비교 (M1 이후 유지)",
        "",
        "| `strategy` | `oversell_count` | `hold_p99_ms` | `hold_throughput_rps` |",
        "|---|---|---|---|",
        *[f"| {s} | {ov} | {p99} | {thr} |" for s, ov, p99, thr in strat_rows],
        "",
        "- `oversell_count`는 3회 합계(도착률 실행 + 버스트 잔존), 나머지는 중앙값 (최소~최대).",
        "",
        "## 대기열 (M2 이후)",
        "",
        "값은 주간 오픈런, 괄호는 bench-m2 주간 봇(`rank_drift_count`는 새로고침 폭풍).",
        "",
        "| 지표 | 값 | 시나리오 설정 | 목표 |",
        "|---|---|---|---|",
        f"| `abandon_rate` | {wb(*queue['abandon_rate'], 1, True)} | "
        f"{fmt(abandon_p, 0, True) if abandon_p is not None else NA} | 복원 검증 |",
        f"| `fifo_violation_rate` | {wb(*queue['fifo_violation_rate'], 2, True)} | | ≤ 1% |",
        f"| `admission_delay_p50_s` / `p99_s` | {fmt(queue['admission_delay_p50_s'][0])} / "
        f"{fmt(queue['admission_delay_p99_s'][0])} (벤치 {fmt(queue['admission_delay_p50_s'][1])} / "
        f"{fmt(queue['admission_delay_p99_s'][1])}) | | 보고 |",
        f"| `admission_tick_p99_ms` | {wb(*queue['admission_tick_p99_ms'])} | | ≤ 500 |",
        f"| `admission_rate_actual` | {wb(*queue['admission_rate_actual'])} | {fmt(admissions_cfg)}/s | 설정값 ±5% |",
        f"| `rank_drift_count` | {wb(*queue['rank_drift_count'])} | | 0 |",
        "",
        "## 결제 (M3 이후)",
        "",
        f"값은 주간 오픈런, 괄호는 bench-m2 주간 봇. 주간 오픈런 Fake PG 주입: {injected}.",
        "",
        "| 지표 | 값 | 목표 |",
        "|---|---|---|",
        f"| `hold_to_confirm_rate` | {wb(*pay['hold_to_confirm_rate'], 1, True)} | 포기율 12%로 역산한 기대값과 비교 "
        f"(1 − `payment_abandon_probability` = {expected_htc}, DECLINE·만료 전) |",
        f"| `confirm_expire_race_occurred` | {wb(*pay['confirm_expire_race_occurred'])} | Fake PG 주입량과 비교 "
        f"(TIMEOUT {fmt(faults.get('TIMEOUT'))}, `unknown_count` {fmt(pay['unknown_count'][0])}) |",
        f"| `unknown_dwell_p99_s` | {wb(*pay['unknown_dwell_p99_s'])} | ≤ 60 |",
        f"| `webhook_ack_p99_ms` | {wb(*pay['webhook_ack_p99_ms'])} | ≤ 1,000 |",
        f"| `webhook_stale_count` / `webhook_duplicate_count` | {fmt(pay['webhook_stale_count'][0])} / "
        f"{fmt(pay['webhook_duplicate_count'][0])} (벤치 {fmt(pay['webhook_stale_count'][1])} / "
        f"{fmt(pay['webhook_duplicate_count'][1])}) | 보고 |",
        "",
        "## 퍼널 (2차 이후, `source_label` 명시)",
        "",
        "주간 오픈런 k6 회원 수(`queue_entered` → `queue_admitted` → `hold_success` → `payment_started` → `confirm_ok`), "
        "괄호는 app(queue-integrity). source_label: synthetic. confirmed는 confirm이 바로 CONFIRMED를 돌려준 수라 "
        "UNKNOWN 뒤 janitor가 확정한 건은 빠진다.",
        "",
        "| 단계 | 인원 | 전환율 (enqueued 대비) |",
        "|---|---|---|",
        *[f"| {name} | {n} | {share} |" for name, n, share in funnel_rows],
        "",
        f"복원 검증: `recovery_error_pp` = (abandon / payment_abandon / macro) = "
        f"{fmt(rec['abandon'], 2)} / {fmt(rec['payment_abandon'], 2)} / {NA} %p. "
        "app·k6 값 − 시나리오 설정(부호 유지); 2차 마트 추정이 아니며 매크로는 측정 수단이 없다.",
        "",
        "## 이번 주 배운 것",
        "",
        f"- {TO_WRITE}: 문제 재현 테스트 → 해결 → 숫자 → 한계. 각 항목에 PR 링크.",
        "",
        "## 미해결",
        "",
        f"- {TO_WRITE}: 보상 실패, 수동 처리 대기, 다음 주로 넘기는 것.",
        "",
        "## 재현",
        "",
        "```bash",
        f"git checkout {meta.get('app_commit') or '<app_commit>'}",
        "./gradlew build",
        "docker compose -f infra/compose/compose.yaml --profile core up -d --wait",
        "OPENRUN_QUEUE_ADMISSIONS_PER_SECOND=100 OPENRUN_PAYMENT_PG=fake OPENRUN_FAKE_PG_WEBHOOKS=true ./gradlew :apps:api:bootRun &",
        f"DURATION_S={meta.get('duration_s', 1200)} "
        + (f"MEMBERS={meta.get('members')} " if meta.get("members_from") == "env" else "")
        + f"ADMISSIONS_PER_SECOND={admissions_cfg or 100} SETTLE_S={meta.get('settle_s', 900)} "
        f"SCENARIO={scenario_file} load/weekly-openrun.sh",
        "# 벤치마크 (API 설정은 .github/workflows/bench-m1.yml · bench-m2.yml의 env)",
        "load/bench-m1.sh 3",
        "load/bench-m2.sh",
        f"python3 load/report/weekly_report.py --date {args.date} --bench-m1 <dir> --bench-m2 <dir>",
        "```",
        "",
    ]
    md = "\n".join(lines)

    os.makedirs(args.reports, exist_ok=True)
    with open(os.path.join(args.reports, f"{args.date}.md"), "w", encoding="utf-8") as f:
        f.write(md)
    with open(os.path.join(args.reports, f"{args.date}.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write("\n")
    write_index(args.reports)
    print(md)
    return 0


def write_index(reports):
    """docs/reports/README.md: reports newest first."""
    rows = []
    for md in glob.glob(os.path.join(reports, "????-??-??.md")):
        date = os.path.basename(md)[:10]
        data = read_json(os.path.join(reports, f"{date}.json"))
        rows.append((date, data.get("report_no"), data.get("summary_sentence") or "(수동 작성 리포트)"))
    rows.sort(reverse=True)
    lines = [
        "# 주간 오픈런 리포트",
        "",
        "<!-- load/report/weekly_report.py가 매주 다시 쓴다. 손으로 고치지 않는다. -->",
        "",
        "매주 토요일 `weekly-openrun` 워크플로가 벤치마크 3회 중앙값과 주간 오픈런 1회 결과로 발행한다(ADR-0008 §3). "
        "지표 정의는 [metrics.md](../metrics.md).",
        "",
        "| 날짜 | # | 한 줄 요약 | 리포트 |",
        "|---|---|---|---|",
        *[f"| {d} | {('#' + str(n)) if n else NA} | {s} | [{d}.md]({d}.md) |" for d, n, s in rows],
        "",
        "- 러너 실행 기록(벤치 원본 표): [runs/](runs/)",
        "- 템플릿: [_template.md](_template.md)",
        "",
    ]
    with open(os.path.join(reports, "README.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
