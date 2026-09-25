#!/usr/bin/env python3
import html
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _instrumented_test_report import summarize  # noqa: E402

patterns = [
    "app/build/outputs/androidTest-results/connected/**/*.xml",
    "**/build/outputs/androidTest-results/connected/**/*.xml",
]
summary = summarize(patterns)

if not summary.files or not summary.ran_anything:
    status = "NOT_EXECUTED"
elif not summary.ok:
    status = "FAIL"
else:
    status = "PASS"

lines = [
    "# BrainCode — Relatório E2E",
    "",
    f"**Resultado:** {status}",
    "",
    f"- Testes: **{summary.tests}**",
    f"- Passaram: **{summary.passed}**",
    f"- Falharam: **{summary.failures}**",
    f"- Erros: **{summary.errors}**",
    f"- Ignorados (capacidade opcional indisponível): **{summary.skipped}**",
    "",
    "## Jornadas",
    "",
    "| Resultado | Classe | Teste | Tempo |",
    "|---|---|---|---|",
]
for result, cls, name, duration in summary.rows:
    lines.append(f"| {result} | {html.escape(cls)} | {html.escape(name)} | {duration}s |")

if not summary.rows:
    lines += ["", "> Nenhum resultado XML de instrumentação foi encontrado."]

if summary.skipped:
    lines += [
        "",
        "> Testes ignorados representam capacidades de plataforma opcionais "
        "ausentes na imagem do emulador (ex.: provider JCA Ed25519) e não "
        "são tratados como falha da jornada do BrainCode.",
    ]

os.makedirs("e2e-report", exist_ok=True)
open("e2e-report/BrainCode-E2E-Report.md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("\n".join(lines))

if status in ("FAIL", "NOT_EXECUTED"):
    # NOT_EXECUTED also fails the build: the emulator step now runs with
    # continue-on-error so a skipped-but-otherwise-healthy Ed25519 test
    # doesn't block the pipeline, but that means a genuine infra failure
    # (emulator never booted, no tests ran at all) must be caught here
    # instead, or it would silently pass the job.
    raise SystemExit(1)
