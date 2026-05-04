package dev.rdh.bf.opt

import dev.rdh.bf.*

internal sealed interface AnalysisOp

internal data class AnalysisStep(val op: Op) : AnalysisOp {
    init {
        require(op !is Conditional && op !is Loop) { "control ops must use analysis-specific nodes" }
    }
}

internal data class AnalysisConditional(val offset: Int, val body: List<AnalysisOp>) : AnalysisOp

internal data class AnalysisLoop(val offset: Int, val body: List<AnalysisOp>) : AnalysisOp

internal data class AnalysisSummary(
    val body: List<AnalysisOp>,
    val summary: Optimizer.LoopSummary,
) : AnalysisOp

internal fun Op.toAnalysisOp(): AnalysisOp = when (this) {
    is Conditional -> AnalysisConditional(offset, body.map(Op::toAnalysisOp))
    is Loop -> AnalysisLoop(offset, body.map(Op::toAnalysisOp))
    else -> AnalysisStep(this)
}
