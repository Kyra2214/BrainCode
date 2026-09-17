#!/usr/bin/env python3
import glob
import html
import os
import xml.etree.ElementTree as ET

patterns = [
    "app/build/outputs/androidTest-results/connected/**/*.xml",
    "**/build/outputs/androidTest-results/connected/**/*.xml",
]
files = []
for pattern in patterns:
    files.extend(glob.glob(pattern, recursive=True))
files = sorted(set(files))

tests = failures = errors = skipped = 0
rows = []

for path in files:
    try:
        root = ET.parse(path).getroot()
    except Exception:
        continue
    for case in root.iter("testcase"):
        tests += 1
        name = case.attrib.get("name", "unknown")
        cls = case.attrib.get("classname", "")
        status = "PASS"
        if case.find("failure") is not None:
            failures += 1
            status = "FAIL"
        elif case.find("error") is not None:
            errors += 1
            status = "ERROR"
        elif case.find("skipped") is not None:
            skipped += 1
            status = "SKIPPED"
        rows.append((status, cls, name, case.attrib.get("time", "")))

passed = tests - failures - errors - skipped
if not files:
    status = "NOT_EXECUTED"
elif tests and failures == 0 and errors == 0:
    status = "PASS"
else:
    status = "FAIL"

lines = [
    "# BrainCode — Relatório E2E",
    "",
    f"**Resultado:** {status}",
    "",
    f"- Testes: **{tests}**",
    f"- Passaram: **{passed}**",
    f"- Falharam: **{failures}**",
    f"- Erros: **{errors}**",
    f"- Ignorados: **{skipped}**",
    "",
    "## Jornadas",
    "",
    "| Resultado | Classe | Teste | Tempo |",
    "|---|---|---|---|",
]
for result, cls, name, duration in rows:
    lines.append(f"| {result} | {html.escape(cls)} | {html.escape(name)} | {duration}s |")

if not rows:
    lines += ["", "> Nenhum resultado XML de instrumentação foi encontrado."]

os.makedirs("e2e-report", exist_ok=True)
open("e2e-report/BrainCode-E2E-Report.md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("\n".join(lines))
if status != "PASS":
    raise SystemExit(1)
