#!/usr/bin/env bash
# Mutation testing of the backend (PIT) with a per-package summary at the end.
#
# Usage:
#   ./run/mutation.sh [-- <extra gradle args>]
#   ./run/mutation.sh --summary        # only summarise the last report, no run
#
# What is mutated (chat and git) and how is configured in the `pitest` block of
# backend/build.gradle. Only *Test suites are run — no Docker. The run is slow
# (~35 min on 4 cores), so it is not part of `pre-pr`.
#
# The toolchain decisions (Gradle, Java 21 fallback) stay in run/test.sh; this
# script calls its `mutation` suite and adds the summary.
# Report: backend/build/reports/pitest/index.html
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPORT="$ROOT/backend/build/reports/pitest/mutations.xml"

summarise() {
  if [ ! -f "$REPORT" ]; then
    echo "No report at $REPORT — run without --summary first." >&2
    return 1
  fi
  command -v python3 > /dev/null 2>&1 || { echo "python3 not found — open the HTML report instead." >&2; return 0; }
  python3 - "$REPORT" <<'PY'
import re, sys, collections
xml = open(sys.argv[1], encoding="utf-8").read()
by_pkg = collections.defaultdict(collections.Counter)
by_cls = collections.Counter()
for m in re.finditer(r"<mutation detected='\w+' status='(\w+)'.*?<mutatedClass>(.*?)</mutatedClass>", xml, re.S):
    status, cls = m.groups()
    pkg = cls.rsplit(".", 1)[0].replace("io.github.trialiya.kb.", "")
    by_pkg[pkg][status] += 1
    if status in ("SURVIVED", "NO_COVERAGE"):
        by_cls[cls.rsplit(".", 1)[-1].split("$")[0]] += 1
print(f"\n{'package':34} {'total':>6} {'killed':>7} {'alive':>6} {'nocov':>6}  score")
grand = collections.Counter()
for pkg, c in sorted(by_pkg.items()):
    total = sum(c.values())
    killed = c["KILLED"] + c["TIMED_OUT"]
    grand.update(c)
    print(f"{pkg:34} {total:6} {killed:7} {c['SURVIVED']:6} {c['NO_COVERAGE']:6}  {100 * killed // total}%")
total = sum(grand.values())
killed = grand["KILLED"] + grand["TIMED_OUT"]
if total:
    print(f"{'TOTAL':34} {total:6} {killed:7} {grand['SURVIVED']:6} {grand['NO_COVERAGE']:6}  {100 * killed // total}%")
print("\nMost surviving mutants (survived + no coverage):")
for name, n in by_cls.most_common(10):
    print(f"  {n:4}  {name}")
PY
}

if [ "${1:-}" = "--summary" ]; then
  summarise
  exit
fi

"$SCRIPT_DIR/test.sh" mutation "$@"
summarise
echo
echo "HTML report: backend/build/reports/pitest/index.html"
