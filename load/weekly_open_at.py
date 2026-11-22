#!/usr/bin/env python3
"""openAt for load/weekly-openrun.sh (ADR-0008 §2): when the weekly showtime opens, as an aware UTC datetime.

Rules, in order:
  1. OPEN_DELAY_S non-empty      -> now + delay (local and workflow_dispatch test runs)
  2. OPEN_AT non-empty           -> that ISO-8601 instant; it must carry an offset (Z or +hh:mm)
  3. otherwise                   -> 20:00 Asia/Seoul on the KST date of now (fixed +09:00, no DST)
Then the bound: a target that is past, closer than MIN_LEAD_S, or more than MAX_WAIT_S ahead becomes now + MIN_LEAD_S
and is reported as adjusted. The 19:40 KST cron makes the default 20 min away; a scheduled run that GitHub starts
hours late (2026-11-21: 02:45 KST Sunday) would otherwise target the next day's 20:00 and wait ~17 h for it, so the
wait is capped at MAX_WAIT_S instead.

usage: weekly_open_at.py OPEN_AT OPEN_DELAY_S MIN_LEAD_S MAX_WAIT_S [--now ISO] [--reason]
  prints "<openAt ISO ms UTC> <true|false>"; with --reason a third token: past | too_far | none.
"""
import argparse
import datetime
import sys

KST = datetime.timezone(datetime.timedelta(hours=9))  # Asia/Seoul has no DST
DEFAULT_OPEN_HOUR_KST = 20
UTC = datetime.timezone.utc


def _target(open_at, delay, now):
    if delay:
        return now + datetime.timedelta(seconds=int(delay))
    if open_at:
        at = datetime.datetime.fromisoformat(open_at.replace("Z", "+00:00"))
        if at.tzinfo is None:
            raise ValueError(f"OPEN_AT needs an offset: {open_at}")
        return at
    return now.astimezone(KST).replace(hour=DEFAULT_OPEN_HOUR_KST, minute=0, second=0, microsecond=0)


def resolve(open_at, delay, min_lead_s, max_wait_s, now):
    """(openAt in UTC, reason) where reason is None (kept), "past" (past or closer than min_lead_s) or "too_far"."""
    if now.tzinfo is None:
        raise ValueError("now must be timezone-aware")
    if min_lead_s < 0 or max_wait_s < min_lead_s:
        raise ValueError(f"need 0 <= MIN_LEAD_S <= MAX_WAIT_S, got {min_lead_s} and {max_wait_s}")
    now = now.astimezone(UTC)
    target = _target(open_at, delay, now)
    reason = None
    if target < now + datetime.timedelta(seconds=min_lead_s):
        reason = "past"
    elif target > now + datetime.timedelta(seconds=max_wait_s):
        reason = "too_far"
    if reason is not None:
        target = now + datetime.timedelta(seconds=min_lead_s)
    return target.astimezone(UTC), reason


def compute(open_at, delay, min_lead_s, max_wait_s, now):
    """(openAt in UTC, adjusted)."""
    target, reason = resolve(open_at, delay, min_lead_s, max_wait_s, now)
    return target, reason is not None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("open_at", help="OPEN_AT (ISO-8601 with offset) or empty")
    ap.add_argument("delay", help="OPEN_DELAY_S or empty")
    ap.add_argument("min_lead_s", type=int)
    ap.add_argument("max_wait_s", type=int)
    ap.add_argument("--now", help="ISO-8601 instant to use instead of the clock (tests)")
    ap.add_argument("--reason", action="store_true", help="append the adjustment reason: past | too_far | none")
    args = ap.parse_args(argv)
    now = datetime.datetime.fromisoformat(args.now.replace("Z", "+00:00")) if args.now else datetime.datetime.now(UTC)
    try:
        target, reason = resolve(args.open_at, args.delay, args.min_lead_s, args.max_wait_s, now)
    except ValueError as e:
        sys.exit(str(e))
    out = [target.isoformat(timespec="milliseconds"), "true" if reason else "false"]
    if args.reason:
        out.append(reason or "none")
    print(*out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
