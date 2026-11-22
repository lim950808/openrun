"""load/weekly_open_at.py: the openAt rule of the weekly open run (ADR-0008 §2).

run: python3 -m unittest discover -s load -p 'test_*.py' -t .
"""
import datetime
import os
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from weekly_open_at import compute, resolve  # noqa: E402

UTC = datetime.timezone.utc
MIN_LEAD_S = 60
MAX_WAIT_S = 1800
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "weekly_open_at.py")


def utc(iso):
    return datetime.datetime.fromisoformat(iso.replace("Z", "+00:00"))


class WeeklyOpenAtTest(unittest.TestCase):
    def test_M4_4_delay_wins_over_open_at(self):
        now = utc("2026-11-21T10:40:00Z")
        at, adjusted = compute("2026-11-21T11:00:00+00:00", "300", MIN_LEAD_S, MAX_WAIT_S, now)
        self.assertEqual(at, now + datetime.timedelta(seconds=300))
        self.assertFalse(adjusted)

    def test_M4_4_open_at_with_offset_is_kept_in_utc(self):
        now = utc("2026-11-21T10:40:00Z")
        at, adjusted = compute("2026-11-21T20:00:00+09:00", "", MIN_LEAD_S, MAX_WAIT_S, now)
        self.assertEqual(at, utc("2026-11-21T11:00:00Z"))
        self.assertEqual(at.tzinfo, UTC)
        self.assertFalse(adjusted)

    def test_M4_4_open_at_without_offset_errors(self):
        with self.assertRaises(ValueError):
            compute("2026-11-21T20:00:00", "", MIN_LEAD_S, MAX_WAIT_S, utc("2026-11-21T10:40:00Z"))

    def test_M4_4_default_on_time_run_targets_2000_kst(self):
        # cron 10:40 UTC = 19:40 KST Saturday: 20:00 KST is 20 min away, inside MAX_WAIT_S.
        now = utc("2026-11-21T10:40:00Z")
        at, adjusted = compute("", "", MIN_LEAD_S, MAX_WAIT_S, now)
        self.assertEqual(at, utc("2026-11-21T11:00:00Z"))
        self.assertFalse(adjusted)

    # 재현 케이스(02:45 KST 시작)는 CLAUDE.md 규칙 5에 따라 사람이 다시 쓸 후보
    def test_M4_4_default_after_midnight_kst_opens_now_plus_min_lead(self):
        # GitHub Actions run 44931813294 started 17:45:52 UTC = 02:45 KST Sunday; "today 20:00 KST" was ~17 h away
        # and the script waited until timeout-minutes cancelled it.
        now = utc("2026-11-21T17:45:52Z")
        at, adjusted = compute("", "", MIN_LEAD_S, MAX_WAIT_S, now)
        self.assertEqual(at, now + datetime.timedelta(seconds=MIN_LEAD_S))
        self.assertTrue(adjusted)
        self.assertEqual(resolve("", "", MIN_LEAD_S, MAX_WAIT_S, now)[1], "too_far")

    def test_M4_4_default_past_2000_kst_opens_now_plus_min_lead(self):
        now = utc("2026-11-21T11:30:00Z")  # 20:30 KST
        at, adjusted = compute("", "", MIN_LEAD_S, MAX_WAIT_S, now)
        self.assertEqual(at, now + datetime.timedelta(seconds=MIN_LEAD_S))
        self.assertTrue(adjusted)
        self.assertEqual(resolve("", "", MIN_LEAD_S, MAX_WAIT_S, now)[1], "past")

    def test_M4_4_closer_than_min_lead_counts_as_past(self):
        now = utc("2026-11-21T10:59:30Z")  # 30 s before 20:00 KST
        at, adjusted = compute("", "", MIN_LEAD_S, MAX_WAIT_S, now)
        self.assertEqual(at, now + datetime.timedelta(seconds=MIN_LEAD_S))
        self.assertTrue(adjusted)

    def test_M4_4_rejects_min_lead_above_max_wait(self):
        with self.assertRaises(ValueError):
            compute("", "", 120, 60, utc("2026-11-21T10:40:00Z"))

    def test_M4_4_cli_prints_open_at_and_adjusted(self):
        out = subprocess.run(
            [sys.executable, SCRIPT, "", "", str(MIN_LEAD_S), str(MAX_WAIT_S), "--now", "2026-11-21T10:40:00Z"],
            check=True, capture_output=True, text=True,
        ).stdout.split()
        self.assertEqual(out, ["2026-11-21T11:00:00.000+00:00", "false"])

    def test_M4_4_cli_reason_flag_appends_third_token(self):
        out = subprocess.run(
            [sys.executable, SCRIPT, "", "", str(MIN_LEAD_S), str(MAX_WAIT_S), "--now", "2026-11-21T17:45:52Z",
             "--reason"],
            check=True, capture_output=True, text=True,
        ).stdout.split()
        self.assertEqual(out, ["2026-11-21T17:46:52.000+00:00", "true", "too_far"])

    def test_M4_4_cli_open_at_without_offset_exits_nonzero(self):
        r = subprocess.run(
            [sys.executable, SCRIPT, "2026-11-21T20:00:00", "", str(MIN_LEAD_S), str(MAX_WAIT_S)],
            capture_output=True, text=True,
        )
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("OPEN_AT needs an offset", r.stderr)


if __name__ == "__main__":
    unittest.main()
