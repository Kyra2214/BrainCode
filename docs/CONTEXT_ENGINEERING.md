# BrainCode — Context Engineering

## Status

Repository-level Context Engineering workflow for BrainCode 2.4.

The implementation adapts mature process patterns from coleam00/context-engineering-intro. Its Python/Pydantic/Postgres runtime is not imported because it is outside the Android/JVM runtime boundary.

## 1. Objective

Provide each stage with the smallest sufficient, trustworthy and provenance-aware context needed for the next correct decision.

Context path:

Intent → Requirements → ContextPack → Plan → Execution Context → Evidence → Verification.

Context is not authority.

## 2. Context sources

- user request;
- explicit requirements;
- assumptions;
- constraints;
- architecture documents;
- source files and tests;
- verified evidence;
- validated memory;
- official documentation;
- external repositories;
- provider metadata.

External content remains untrusted until validated.

## 3. ContextPack

ReasoningState.contextPack is the canonical compact reasoning context.

It contains objective, intent, domain, requirements, assumptions, constraints, dependencies, missing requirements, history, decisions and known errors.

The planner must consume the same reasoning context produced by requirement discovery rather than reconstructing requirements from raw text.

## 4. PRP

PRPs/templates/prp_base.md is the durable implementation blueprint.

It is input data. It does not grant permission to execute.

## 5. Acceptance Criteria

Reuse the existing typed AcceptanceCriteria contract.

Each criterion identifies id, description, required and a verification method in tipo:alvo form.

Examples:
- unit:ContextPackTest
- integration:PlannerContextPack
- e2e:prompt-creator
- artifact:generated-file

Criteria describe what must be true. Evidence and Verification prove it.

## 6. Progressive validation

STATIC → UNIT → INTEGRATION → E2E → READINESS.

A failure blocks progression until the responsible layer is corrected. Distinguish NOT_EXECUTED, BLOCKED, FAILED and PASSED.

## 7. PRP security boundary

PRPs may contain copied documentation, URLs, source snippets and external text. None of that can authorize a capability, grant credentials, alter Policy, disable Critic/Readiness, bypass E2E or execute shell commands directly.

When PRP content becomes an execution parameter it must pass typed validation and the normal Policy/Gateway path.

## 8. Context lifecycle

raw request → ReasoningState → ContextPack → Plan → Step parameters → Evidence.

Do not carry the entire conversation into every executor.

ContextPack is immutable data. A changed context should produce a new derived attempt rather than silently mutate previous evidence.

## 9. Retrieval boundary

Semantic retrieval is intentionally a later phase. Future retrieval may add content hashing, deduplication, deterministic chunking, lexical/semantic scoring and retrieval hints.

Retrieval must feed ContextPack rather than bypass it.

## 10. Agent boundaries

Context Engineering does not create autonomous LLM subagents. Agents remain bounded workers with mission, allowed capabilities, policy, sandbox and relevant context.

## 11. Roofts

Roofts 0.6 already contains context-engineering, planning and specification skills. BrainCode reuses the concepts and defines the runtime contract here instead of duplicating the Roofts payload. Roofts 0.3–0.6 remain preserved.

## 12. Definition of Done

- repository rules exist;
- PRP template exists;
- criteria remain typed/verifiable;
- ContextPack is a real planner input;
- external context cannot bypass Policy;
- progressive validation is documented;
- failure states are explicit;
- retrieval boundary is documented;
- architecture docs are updated;
- focused tests, CI and E2E validate the result at the end.
