package com.brain.behavior

enum class FixVerifyLearnStatus { COMPLETED, BLOCKED, FAILED }

data class FixVerifyLearnResult<S, P, F, L>(
    val status: FixVerifyLearnStatus,
    val scan: S? = null,
    val plan: P? = null,
    val fix: F? = null,
    val verification: VerificationResult? = null,
    val learned: L? = null,
    val issues: List<String> = emptyList()
) {
    val completed: Boolean get() = status == FixVerifyLearnStatus.COMPLETED
}

interface ScanStage<S> { fun scan(): S }
interface FixPlanStage<S, P> { fun plan(scan: S): P }
interface FixStage<P, F> { fun fix(plan: P): F }
interface VerifyStage<F> { fun verify(fix: F): VerificationResult }
interface LearnStage<F, L> { fun learn(fix: F, verification: VerificationResult): L }

/** Ciclo universal de correção: não aprende nem mascara resultado vazio/falha. */
class FixVerifyLearn<S, P, F, L>(
    private val scanner: ScanStage<S>,
    private val planner: FixPlanStage<S, P>,
    private val fixer: FixStage<P, F>,
    private val verifier: VerifyStage<F>,
    private val learner: LearnStage<F, L>
) {
    fun run(): FixVerifyLearnResult<S, P, F, L> {
        val scan = runCatching { scanner.scan() }.getOrElse {
            return FixVerifyLearnResult(FixVerifyLearnStatus.FAILED, issues = listOf("scan: ${it.message.orEmpty()}"))
        }
        val plan = runCatching { planner.plan(scan) }.getOrElse {
            return FixVerifyLearnResult(FixVerifyLearnStatus.FAILED, scan, issues = listOf("plan: ${it.message.orEmpty()}"))
        }
        val fix = runCatching { fixer.fix(plan) }.getOrElse {
            return FixVerifyLearnResult(FixVerifyLearnStatus.FAILED, scan, plan, issues = listOf("fix: ${it.message.orEmpty()}"))
        }
        val verification = runCatching { verifier.verify(fix) }.getOrElse {
            return FixVerifyLearnResult(FixVerifyLearnStatus.FAILED, scan, plan, fix, issues = listOf("verify: ${it.message.orEmpty()}"))
        }
        if (!verification.passed) {
            return FixVerifyLearnResult(FixVerifyLearnStatus.BLOCKED, scan, plan, fix, verification, issues = listOf("verification did not pass"))
        }
        val learned = runCatching { learner.learn(fix, verification) }.getOrElse {
            return FixVerifyLearnResult(FixVerifyLearnStatus.FAILED, scan, plan, fix, verification, issues = listOf("learn: ${it.message.orEmpty()}"))
        }
        return FixVerifyLearnResult(FixVerifyLearnStatus.COMPLETED, scan, plan, fix, verification, learned)
    }

}
