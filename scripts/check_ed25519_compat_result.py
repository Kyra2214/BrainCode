#!/usr/bin/env python3
"""Evaluate the Ed25519 compatibility instrumentation result for one emulator
API level and decide whether the Marketplace Compatibility job should fail.

Ed25519CompatibilityInstrumentedTest uses org.junit.Assume to turn "this
device image has no Ed25519 JCA provider" into a JUnit *skip*, not a
failure — on-device availability of an optional crypto provider is not
something the app controls. This script is the CI-side counterpart: it reads
the instrumentation XML produced by `connectedDebugAndroidTest` and only
fails the job on a genuine <failure>/<error>. A run where the single test was
skipped, or where nothing ran because the emulator itself never came up, is
reported clearly either way so a real infra problem is still visible in the
logs, but only actual test failures block the workflow.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _instrumented_test_report import summarize  # noqa: E402

patterns = [
    "app/build/outputs/androidTest-results/connected/**/*.xml",
    "**/build/outputs/androidTest-results/connected/**/*.xml",
]
summary = summarize(patterns)

api_level = os.environ.get("BRAINCODE_API_LEVEL", "?")

print(f"Ed25519 compatibility check — API {api_level}")
print(f"  testes: {summary.tests}, passaram: {summary.passed}, "
      f"falharam: {summary.failures}, erros: {summary.errors}, ignorados: {summary.skipped}")

for status, cls, name, duration in summary.rows:
    print(f"  [{status}] {cls}#{name} ({duration}s)")

if not summary.ran_anything:
    print(
        "  ATENÇÃO: nenhum resultado de instrumentação foi encontrado — isso "
        "normalmente indica que o emulador não subiu ou o filtro de classe "
        "não encontrou o teste, e precisa ser investigado mesmo sem uma "
        "falha de asserção registrada.",
        file=sys.stderr,
    )
    raise SystemExit(1)

if not summary.ok:
    print("  Resultado: FAIL (falha real de teste)", file=sys.stderr)
    raise SystemExit(1)

if summary.skipped:
    print(
        "  Resultado: PASS (provider Ed25519 opcional indisponível nesta "
        "imagem de emulador; não bloqueia o compatibility gate)"
    )
else:
    print("  Resultado: PASS")
