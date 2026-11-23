"""load/report/weekly_report.py: bench runner spec and the k6 dropped_ratio (요구사항 §4 부하 생성기 포화 판정).

run: python3 -m unittest discover -s load -p 'test_*.py' -t .
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "report"))
import weekly_report  # noqa: E402
from weekly_report import bench_runner_text, dropped_footnote, dropped_ratio, hold_summary  # noqa: E402

# 2026-11-21 azure bench-m1 CONDITIONAL_UPDATE 도착률 실행 3회
AZURE_RUNS = [
    {"dropped_iterations": d, "planned_attempt_rps": 2785.7, "duration_s": 120} for d in (124815, 126081, 125681)
]
RUNNER_SPEC = """nproc=4
               total        used        free      shared  buff/cache   available
Mem:              15           2          11           0           1          12
kernel=7.0.0-1020-azure
"""


class DroppedRatioTest(unittest.TestCase):
    def test_M4_5_dropped_ratio_sums_dropped_over_planned(self):
        r, dropped, planned = dropped_ratio(AZURE_RUNS)
        self.assertEqual(dropped, 376577)
        self.assertEqual(round(planned), 1002852)
        self.assertAlmostEqual(r, 0.3755, places=4)

    def test_M4_5_dropped_ratio_without_runs_is_none(self):
        self.assertIsNone(dropped_ratio([]))

    def test_M4_5_run_missing_planned_rps_is_skipped_from_both_sums(self):
        runs = [*AZURE_RUNS[:2], {"dropped_iterations": 999999, "duration_s": 120}]
        r, dropped, planned = dropped_ratio(runs)
        self.assertEqual(dropped, 124815 + 126081)
        self.assertAlmostEqual(planned, 2785.7 * 120 * 2)
        self.assertAlmostEqual(r, (124815 + 126081) / (2785.7 * 120 * 2))

    def test_M4_5_zero_planned_is_none(self):
        self.assertIsNone(dropped_ratio([{"dropped_iterations": 0, "planned_attempt_rps": 0, "duration_s": 120}]))


class BenchRunnerTextTest(unittest.TestCase):
    def test_M4_5_bench_runner_text_parses_runner_spec(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "runner-spec.txt"), "w", encoding="utf-8") as f:
                f.write(RUNNER_SPEC)
            self.assertEqual(bench_runner_text("bench-m1", d), "bench-m1 4 vCPU · 15GB · kernel 7.0.0-1020-azure")

    def test_M4_5_bench_runner_text_is_na_when_missing(self):
        self.assertEqual(bench_runner_text("bench-m1", None), "bench-m1 n/a")
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(bench_runner_text("bench-m1", d), "bench-m1 n/a")
            self.assertEqual(bench_runner_text("bench-m1", os.path.join(d, "nope")), "bench-m1 n/a")


class SaturationWordingTest(unittest.TestCase):
    def test_M4_5_saturated_footnote_and_summary_defer_the_target(self):
        dr = dropped_ratio(AZURE_RUNS)
        note = dropped_footnote("CONDITIONAL_UPDATE", dr, [0.2, dr[0], 0.4])
        self.assertEqual(
            note,
            "- ² bench-m1 `CONDITIONAL_UPDATE` `dropped_ratio` 37.6% (`dropped_iterations` 376,577 / 계획 1,002,852, "
            "전략 전체 20.0%~40.0%). 5%를 넘어 생성기 포화: 이 잡의 `hold_p99_ms` 목표 판정은 보류하고 같은 잡 안의 "
            "전략 비교만 쓴다(요구사항 §4, ADR-0005 §8).",
        )
        self.assertEqual(
            hold_summary(300.0, dr[0]),
            "`hold_p99_ms` 중앙값 300.0 ms (목표 ≤ 200, 생성기 포화 `dropped_ratio` 37.6%라 판정 보류)",
        )

    def test_M4_5_unsaturated_footnote_keeps_the_target(self):
        dr = (0.004, 1200, 300000.4)
        self.assertEqual(
            dropped_footnote("CONDITIONAL_UPDATE", dr, [0.004]),
            "- ² bench-m1 `CONDITIONAL_UPDATE` `dropped_ratio` 0.4% (`dropped_iterations` 1,200 / 계획 300,000, "
            "전략 전체 0.4%). 5% 이하라 생성기 포화 아님, `hold_p99_ms` 목표 판정 유효(요구사항 §4).",
        )
        self.assertEqual(hold_summary(116.0, 0.004), "`hold_p99_ms` 중앙값 116.0 ms (목표 ≤ 200)")
        self.assertEqual(hold_summary(116.0, None), "`hold_p99_ms` 중앙값 116.0 ms (목표 ≤ 200)")

    def test_M4_5_exactly_five_percent_is_not_saturated(self):
        self.assertEqual(weekly_report.DROPPED_RATIO_MAX, 0.05)
        self.assertIn("목표 판정 유효", dropped_footnote("CONDITIONAL_UPDATE", (0.05, 5, 100), [0.05]))

    def test_M4_5_unknown_ratio_footnote_is_na(self):
        self.assertEqual(
            dropped_footnote("CONDITIONAL_UPDATE", None, []),
            "- ² bench-m1 `dropped_ratio` n/a: 아티팩트가 없거나 `dropped_iterations`·`planned_attempt_rps`가 없다.",
        )


if __name__ == "__main__":
    unittest.main()
