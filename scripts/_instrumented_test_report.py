"""Shared helper for parsing Android instrumentation JUnit XML reports.

Design intent (see Ed25519CompatibilityInstrumentedTest.kt): a test that uses
`org.junit.Assume` to skip itself when an *optional* platform capability is
missing (e.g. the Ed25519 JCA provider on older/odd emulator images) must
never be treated the same as a genuine test failure. JUnit already reports
that outcome as `<skipped/>` in the XML — the bug in this repo was that our
own report scripts then re-interpreted "skipped" as "fail", which is what
turned an intentionally-tolerant test into a hard CI blocker.

This module is the single place that decides pass/fail so every workflow
(CI, UI E2E, Marketplace Compatibility) agrees on the rule:

    FAIL  -> any <failure> or <error> element (a real, unexpected problem)
    SKIP  -> any <skipped/> element (an assumption was not met; informational)
    PASS  -> everything else

A run with zero real failures/errors is considered a pass *even if every
test was skipped*, because a skip means "this optional capability was not
present on this device image", not "the app is broken". The one exception is
when no XML files / no testcases are found at all, which usually means the
test never actually ran (e.g. emulator boot failure, wrong class filter) and
is surfaced separately so callers can decide how to handle it.
"""
from __future__ import annotations

import glob
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field


@dataclass
class TestSummary:
    files: list = field(default_factory=list)
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    rows: list = field(default_factory=list)  # (status, classname, name, time)

    @property
    def passed(self) -> int:
        return self.tests - self.failures - self.errors - self.skipped

    @property
    def ok(self) -> bool:
        """True when there are no genuine failures/errors (skips are fine)."""
        return self.failures == 0 and self.errors == 0

    @property
    def ran_anything(self) -> bool:
        return self.tests > 0


def summarize(patterns) -> TestSummary:
    summary = TestSummary()
    files = sorted(set(path for pattern in patterns for path in glob.glob(pattern, recursive=True)))
    summary.files = files

    for path in files:
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        for case in root.iter("testcase"):
            summary.tests += 1
            name = case.attrib.get("name", "unknown")
            cls = case.attrib.get("classname", "")
            status = "PASS"
            if case.find("failure") is not None:
                summary.failures += 1
                status = "FAIL"
            elif case.find("error") is not None:
                summary.errors += 1
                status = "ERROR"
            elif case.find("skipped") is not None:
                summary.skipped += 1
                status = "SKIPPED"
            summary.rows.append((status, cls, name, case.attrib.get("time", "")))

    return summary
