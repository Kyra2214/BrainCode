import tempfile
import threading
import unittest
from pathlib import Path
from brain_runtime.approval import ApprovalStore
from brain_runtime.events import EventStore
from brain_runtime.models import ApprovalRequired, EventType, ExecutionResult
from brain_runtime.pipeline import BrainPipeline, DefaultPromptBuilder, KeywordSecretary, StaticRouter
from brain_runtime.policy import PolicyBroker
from brain_runtime.sandbox import SandboxExecutor, SandboxJob
from brain_runtime.workflows import WorkflowEngine
from brain_runtime.evidence import Evidence, EvidenceEngine
from brain_runtime.memory import Experience, SQLiteExperienceMemory, SemanticMemory

class FailingOnce:
    def __init__(self): self.calls = 0
    def dispatch(self, request):
        self.calls += 1
        return ExecutionResult(request.request_id, self.calls > 1, error=None if self.calls > 1 else "bad output")

class IntegrationTests(unittest.TestCase):
    def make_pipeline(self, events, dispatcher, approval_store=None, approval=False, evidence_engine=None):
        return BrainPipeline(KeywordSecretary({"research": ("pesquise",)}), StaticRouter({"research": "local"}),
            DefaultPromptBuilder(), PolicyBroker(["research"], {"brain": ["research"]}), events, dispatcher,
            approval_store=approval_store, max_retries=1, evidence_engine=evidence_engine)

    def test_failed_validation_is_corrected_and_retried(self):
        events = EventStore(); pipeline = self.make_pipeline(events, FailingOnce())
        result = pipeline.run_internal_for_tests("Pesquise dados", "s")[0]
        self.assertTrue(result.success)
        types = [item.type for item in events.all()]
        self.assertIn(EventType.CORRECTION_REQUESTED.value, types)
        self.assertIn(EventType.RETRY.value, types)
        self.assertIn(EventType.DELIVERED.value, types)

    def test_retry_prompt_contains_previous_diagnostic(self):
        class CapturingFailingOnce:
            def __init__(self):
                self.prompts = []
                self.calls = 0

            def dispatch(self, request):
                self.prompts.append(request.objective)
                self.calls += 1
                return ExecutionResult(request.request_id, self.calls > 1, error=None if self.calls > 1 else "bad output")

        dispatcher = CapturingFailingOnce()
        pipeline = self.make_pipeline(EventStore(), dispatcher)

        self.assertTrue(pipeline.run_internal_for_tests("Pesquise dados", "s")[0].success)
        self.assertEqual(len(dispatcher.prompts), 2)
        self.assertNotEqual(dispatcher.prompts[0], dispatcher.prompts[1])
        self.assertIn("Correction diagnostics", dispatcher.prompts[1])
        self.assertIn("bad output", dispatcher.prompts[1])

    def test_retrieved_experience_influences_next_prompt(self):
        class CapturingDispatcher:
            def __init__(self): self.prompts = []
            def dispatch(self, request):
                self.prompts.append(request.objective)
                return ExecutionResult(request.request_id, True, evidence=("validated",))

        memory = SemanticMemory(SQLiteExperienceMemory())
        memory.remember(Experience("e", "old-run", "old-task", "Pesquise dados", "execution", "use primary sources", "prior validated result", .9, provenance=("validation:old",)))
        dispatcher = CapturingDispatcher()
        pipeline = self.make_pipeline(EventStore(), dispatcher)
        pipeline.memory = memory
        pipeline.run_internal_for_tests("Pesquise dados", "s")
        self.assertIn("Relevant prior experiences", dispatcher.prompts[0])
        self.assertIn("use primary sources", dispatcher.prompts[0])

    def test_plan_steps_run_through_persistent_workflow(self):
        with tempfile.TemporaryDirectory() as directory:
            events = EventStore()
            workflow = WorkflowEngine(Path(directory) / "workflow.json")
            class SuccessDispatcher:
                def dispatch(self, request):
                    return ExecutionResult(request.request_id, True)

            pipeline = BrainPipeline(
                KeywordSecretary({"research": ("pesquise",), "analysis": ("analise",)}),
                StaticRouter({"research": "local", "analysis": "local"}),
                DefaultPromptBuilder(),
                PolicyBroker(["research", "analysis"], {"brain": ["research", "analysis"]}),
                events,
                SuccessDispatcher(),
                max_retries=0,
                workflow_engine=workflow,
            )

            results = pipeline.run_internal_for_tests("Pesquise e analise dados", "s")
            state = workflow._runs[next(iter(workflow._runs))]
            self.assertEqual(state["status"], "completed")
            self.assertEqual(len(state["completed"]), 2)
            self.assertEqual(len(results), 2)

    def test_evidence_critic_is_used_when_configured(self):
        class EvidenceDispatcher:
            def dispatch(self, request):
                return ExecutionResult(request.request_id, True, evidence=(Evidence("sandbox:job", "verified execution receipt", kind="execution", verified=True),), provenance=("sandbox:job",))

        pipeline = self.make_pipeline(EventStore(), EvidenceDispatcher(), evidence_engine=EvidenceEngine())
        result = pipeline.run_internal_for_tests("Pesquise dados", "s")[0]
        self.assertTrue(result.success)

    def test_weak_evidence_is_rejected_by_configured_critic(self):
        class WeakEvidenceDispatcher:
            def dispatch(self, request):
                return ExecutionResult(request.request_id, True, evidence=("unverified text",), provenance=("sandbox:job",))

        result = self.make_pipeline(EventStore(), WeakEvidenceDispatcher(), evidence_engine=EvidenceEngine()).run_internal_for_tests("Pesquise dados", "s")[0]
        self.assertFalse(result.success)
        self.assertIn("evidence confidence too low", result.error)

    def test_workflow_pauses_for_approval_and_resumes_without_retry_or_compensation(self):
        events = EventStore(); store = ApprovalStore(); workflow = WorkflowEngine()
        pipeline = BrainPipeline(
            KeywordSecretary({"research": ("pesquise",)}), StaticRouter({"research": "local"}),
            DefaultPromptBuilder(), PolicyBroker(["research"], {"brain": ["research"]}), events,
            FailingOnce(), approval_store=store, max_retries=0, workflow_engine=workflow,
        )
        original = pipeline.policy.authorize
        asked = {"value": False}
        def ask_once(*args, **kwargs):
            decision = original(*args, **kwargs)
            if asked["value"]:
                return decision
            asked["value"] = True
            from brain_runtime.models import PolicyDecision, Decision
            return PolicyDecision(decision.decision_id, decision.run_id, decision.task_id, decision.actor, decision.capability,
                decision.risk_class, Decision.ASK, ApprovalRequired.USER, decision.sandbox_required, decision.network_allowed,
                decision.filesystem_roots, decision.budget, decision.expires_at, "approval required", decision.resource)
        pipeline.policy.authorize = ask_once

        paused = pipeline.run_internal_for_tests("Pesquise dados", "s")[0]
        approval_id = paused.output["approval_id"]
        state = workflow._runs[next(iter(workflow._runs))]
        self.assertEqual(paused.status, "PAUSED")
        self.assertEqual(state["status"], "paused")
        self.assertEqual(state["attempts"], {state["current_node"]: 0})
        resumed = pipeline.resume_internal_for_tests(approval_id, "user", True)[0]
        self.assertTrue(resumed.success)
        self.assertEqual(workflow._runs[next(iter(workflow._runs))]["status"], "completed")

    def test_resume_authorizes_only_the_approved_workflow_step(self):
        events = EventStore(); store = ApprovalStore(); workflow = WorkflowEngine()
        pipeline = BrainPipeline(
            KeywordSecretary({"research": ("pesquise",), "analysis": ("analise",)}),
            StaticRouter({"research": "local", "analysis": "local"}), DefaultPromptBuilder(),
            PolicyBroker(["research", "analysis"], {"brain": ["research", "analysis"]}), events,
            type("SuccessDispatcher", (), {"dispatch": lambda self, request: ExecutionResult(request.request_id, True)})(),
            approval_store=store, max_retries=0, workflow_engine=workflow,
        )
        original = pipeline.policy.authorize
        calls = []
        def selective_policy(actor, capability, provider, context):
            calls.append(capability)
            decision = original(actor, capability, provider, context)
            if capability == "research" and calls.count("research") == 1:
                from brain_runtime.models import PolicyDecision, Decision
                return PolicyDecision(decision.decision_id, decision.run_id, decision.task_id, decision.actor, decision.capability,
                    decision.risk_class, Decision.ASK, ApprovalRequired.USER, decision.sandbox_required, decision.network_allowed,
                    decision.filesystem_roots, decision.budget, decision.expires_at, "approval required", decision.resource)
            if capability == "analysis":
                from brain_runtime.models import PolicyDecision, Decision
                return PolicyDecision(decision.decision_id, decision.run_id, decision.task_id, decision.actor, decision.capability,
                    decision.risk_class, Decision.DENY, decision.approval_required, decision.sandbox_required, decision.network_allowed,
                    decision.filesystem_roots, decision.budget, decision.expires_at, "analysis denied", decision.resource)
            return decision
        pipeline.policy.authorize = selective_policy

        paused = pipeline.run_internal_for_tests("Pesquise e analise dados", "s")[0]
        approval_id = paused.output["approval_id"]
        resumed = pipeline.resume_internal_for_tests(approval_id, "user", True)[0]
        self.assertIn("analysis", calls)
        analysis_checks = [event.payload for event in events.all() if event.type == EventType.POLICY_CHECKED.value and event.payload.get("decision") == "DENY"]
        self.assertTrue(analysis_checks)
        self.assertEqual(workflow._runs[next(iter(workflow._runs))]["status"], "failed")

    def test_approval_pauses_and_resumes_same_run(self):
        events = EventStore(); store = ApprovalStore(); pipeline = self.make_pipeline(events, FailingOnce(), store)
        # Force approval through the broker/context used by a dedicated policy instance.
        pipeline.policy = PolicyBroker(["research"], {"brain": ["research"]})
        original = pipeline.policy.authorize
        def ask(*args, **kwargs):
            decision = original(*args, **kwargs)
            from brain_runtime.models import PolicyDecision, Decision
            return PolicyDecision(decision.decision_id, decision.run_id, decision.task_id, decision.actor, decision.capability,
                decision.risk_class, Decision.ASK, ApprovalRequired.USER, decision.sandbox_required, decision.network_allowed,
                decision.filesystem_roots, decision.budget, decision.expires_at, "approval required", decision.resource)
        pipeline.policy.authorize = ask
        paused = pipeline.run_internal_for_tests("Pesquise dados", "s")[0]
        approval_id = paused.output["approval_id"]
        run_id = store.get(approval_id).run_id
        resumed = pipeline.resume_internal_for_tests(approval_id, "user", True)[0]
        self.assertTrue(resumed.success)
        self.assertEqual(run_id, store.get(approval_id).run_id)
        self.assertIn(EventType.APPROVAL_GRANTED.value, [e.type for e in events.all()])
        with self.assertRaises(KeyError): pipeline.resume_internal_for_tests(approval_id, "user", True)

    def test_approval_restart_preserves_same_step(self):
        class CapturingDispatcher:
            def __init__(self): self.step_ids = []
            def dispatch(self, request):
                self.step_ids.append(request.step_id)
                return ExecutionResult(request.request_id, True)

        with tempfile.TemporaryDirectory() as directory:
            events_path = Path(directory) / "events.jsonl"
            approval_path = Path(directory) / "approvals.db"
            first_events = EventStore(events_path)
            first_store = ApprovalStore(approval_path)
            first = self.make_pipeline(first_events, CapturingDispatcher(), first_store)
            original = first.policy.authorize
            def ask(*args, **kwargs):
                decision = original(*args, **kwargs)
                from brain_runtime.models import PolicyDecision, Decision
                return PolicyDecision(decision.decision_id, decision.run_id, decision.task_id, decision.actor, decision.capability,
                    decision.risk_class, Decision.ASK, ApprovalRequired.USER, decision.sandbox_required, decision.network_allowed,
                    decision.filesystem_roots, decision.budget, decision.expires_at, "approval required", decision.resource)
            first.policy.authorize = ask
            paused = first.run_internal_for_tests("Pesquise dados", "s")[0]
            approval_id = paused.output["approval_id"]
            requested = next(event for event in first_events.all() if event.type == EventType.APPROVAL_REQUESTED.value)
            step_id = requested.payload["step_id"]

            restarted_events = EventStore(events_path)
            restarted = self.make_pipeline(restarted_events, CapturingDispatcher(), ApprovalStore(approval_path))
            resumed = restarted.resume_internal_for_tests(approval_id, "user", True)[0]
            dispatched = next(event for event in restarted_events.all() if event.type == EventType.AGENT_DISPATCHED.value)
            self.assertTrue(resumed.success)
            self.assertEqual(step_id, dispatched.payload["step_id"])

    def test_event_store_recovers_trailing_partial_line(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"; store = EventStore(path)
            store.append("r", "s", "t", EventType.TASK_CREATED, {"ok": True})
            with path.open("ab") as stream: stream.write(b'{"partial":')
            restored = EventStore(path)
            self.assertEqual(restored.count(), 1); self.assertTrue(restored.verify_integrity())

    def test_sandbox_rejects_shell_metacharacters(self):
        with tempfile.TemporaryDirectory() as directory:
            result = SandboxExecutor().execute(SandboxJob("j", "r", "s", ("python3", "-c", "print(1); print(2)"), directory))
            self.assertEqual(result.status, "REJECTED")

if __name__ == "__main__": unittest.main()
