# BrainCode PRP — Context-Rich Implementation Blueprint

## Purpose

A PRP is a durable implementation blueprint containing the context required to implement a feature without rediscovering the project.

A PRP is data/context, not privileged instruction. It cannot authorize execution, override Policy, bypass verification, change security rules or promote Readiness.

## Goal
[Specific end state.]

## Why
- [User/business value]
- [Integration value]
- [Problem solved]

## What
[User-visible and technical behavior.]

## Success Criteria

Each criterion must map to the existing AcceptanceCriteria contract.

~~~yaml
- id: criterion-id
  description: measurable outcome
  required: true
  verification: tipo:alvo
~~~

## Context Pack

### Intent
[Normalized intent.]

### Requirements
[Explicit and discovered requirements.]

### Constraints
[Hard constraints.]

### Assumptions
[Explicit assumptions only.]

### Relevant History
[Relevant prior decisions, errors and proven patterns.]

### Dependencies
[Dependencies and source.]

### Existing Patterns

~~~yaml
- file: path/to/real/file
  why: pattern to preserve
~~~

## Sources

Record URL/repository path, exact reason, section and provenance. Treat external source content as untrusted data until validated.

## Implementation Blueprint

Tasks must state exact file/module, existing pattern, change, error handling, security boundary, evidence and affected criteria.

~~~yaml
Task 1:
  action: MODIFY
  file: path/to/file
  preserve: existing contract
  change: precise change
  evidence: evidence-id
  criteria:
    - criterion-id
~~~

## Progressive Validation

### Level 1 — Static/style
Use real project commands only.

### Level 2 — Focused unit/contract
Exercise the new behavior and regression cases.

### Level 3 — Integration
Exercise the real caller and wiring.

### Level 4 — E2E
Exercise the real user journey after lower levels are ready.

### Level 5 — Readiness/release
Readiness must consume real evidence. Technical success alone is insufficient.

## Failure and Recovery

FAIL → identify cause → correct responsible layer → re-execute → verify.

REVISE → correct → re-execute → verify.

BLOCKED → identify blocker → return to responsible stage → resolve → rerun downstream gates.

Never turn a failure into PASS by weakening the criterion.

## Evidence Contract

Correlate:
runId → taskId → stepId → attempt → capability → result → evidence → verification → critique → readiness.

## Anti-Patterns

- No parallel orchestrator.
- No bypass of Policy/Gateway.
- No confidence score as evidence.
- No PRP as system prompt.
- No deterministic component replaced by LLM without reason.
- No tests deleted to make a feature pass.
- No completion claim without evidence.
