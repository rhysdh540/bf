package dev.rdh.bf.opt

import dev.rdh.bf.*

internal fun applyLoopSummary(
    state: SymbolicState,
    basePtr: Int,
    summary: Optimizer.LoopSummary,
    guard: Expr,
): Boolean {
    if (summary.prologue.isNotEmpty()) {
        val guardConst = guard as? Const ?: return false
        if (guardConst.value == 0) return true
        if (!state.applyWrites(basePtr, summary.prologue.map { it.offset to it.value })) return false
    }

    if (!state.applyWrites(basePtr, summary.writes.map { it.offset to it.value })) return false
    state.move(summary.pointerDelta)
    return true
}

internal fun validateLoopSummary(
    body: List<AnalysisOp>,
    summary: Optimizer.LoopSummary,
    readCell: (Int) -> Expr,
): Boolean? {
    val raw = executeConcrete(listOf(AnalysisLoop(summary.guardOffset, body)), readCell) ?: return null
    val lowered = executeConcrete(summary, readCell) ?: return null
    if (raw.pointerDelta != lowered.pointerDelta) return false

    val touched = touchedOffsets(listOf(AnalysisLoop(summary.guardOffset, body))) + touchedOffsets(summary)
    for (offset in touched) {
        if (raw.read(offset) != lowered.read(offset)) return false
    }
    return true
}

private data class ConcreteState(
    val entry: (Int) -> Int?,
    val cells: MutableMap<Int, Int> = mutableMapOf(),
    val temps: MutableMap<Temp, Long> = mutableMapOf(),
    var ptrOffset: Int = 0,
) {
    fun read(offset: Int): Int? = cells[offset] ?: entry(offset)
}

private data class ConcreteResult(
    val pointerDelta: Int,
    val cells: Map<Int, Int>,
    val entry: (Int) -> Int?,
) {
    fun read(offset: Int): Int? = cells[offset] ?: entry(offset)
}

private fun executeConcrete(
    ops: List<AnalysisOp>,
    readCell: (Int) -> Expr,
): ConcreteResult? {
    val entry: (Int) -> Int? = { offset ->
        readCell(offset).constantValue()?.let(::truncateByte)
    }
    val state = ConcreteState(entry)
    if (!executeConcreteOps(state, ops)) return null
    return ConcreteResult(state.ptrOffset, state.cells.toMap(), entry)
}

private fun executeConcrete(
    summary: Optimizer.LoopSummary,
    readCell: (Int) -> Expr,
): ConcreteResult? {
    val entry: (Int) -> Int? = { offset ->
        readCell(offset).constantValue()?.let(::truncateByte)
    }
    val state = ConcreteState(entry)
    val guard = Const(state.read(summary.guardOffset) ?: return null)
    if (!applyLoopSummaryConcrete(state, summary, guard)) return null
    return ConcreteResult(state.ptrOffset, state.cells.toMap(), entry)
}

private fun executeConcreteOps(state: ConcreteState, ops: List<AnalysisOp>): Boolean {
    var steps = 0

    fun eval(expr: Expr, ptrOffset: Int = state.ptrOffset): Long? = when (expr) {
        is Const -> expr.value.toLong()
        is Cell -> state.read(ptrOffset + expr.offset)?.toLong()
        is GetTemp -> state.temps[expr.temp]
        is Add -> {
            var sum = 0L
            for (term in expr.terms) {
                sum += eval(term, ptrOffset) ?: return null
            }
            sum
        }

        is Mul -> {
            var product = 1L
            for (factor in expr.factors) {
                product *= eval(factor, ptrOffset) ?: return null
            }
            product
        }

        is Neg -> -(eval(expr.value, ptrOffset) ?: return null)
        is ByteTruncate -> truncateByte(eval(expr.value, ptrOffset) ?: return null).toLong()
        is ExactDiv -> exactDivide(eval(expr.numerator, ptrOffset) ?: return null, expr.divisor.toLong())
        is Choose -> chooseConst(eval(expr.value, ptrOffset) ?: return null, expr.degree)
    }

    fun executeSequence(sequence: List<AnalysisOp>): Boolean {
        for (op in sequence) {
            if (++steps > 1_000_000) return false
            when (op) {
                is AnalysisStep -> when (val step = op.op) {
                    is MovePtr -> state.ptrOffset += step.delta
                    is SetTemp -> state.temps[step.temp] = eval(step.value) ?: return false
                    is Store -> {
                        val absOffset = state.ptrOffset + step.offset
                        val value = truncateByte(eval(step.value) ?: return false)
                        if (value == state.entry(absOffset)) state.cells.remove(absOffset) else state.cells[absOffset] = value
                    }
                    is Read, is Write -> return false
                    else -> error("Unexpected control op in AnalysisStep: $step")
                }

                is AnalysisSummary -> {
                    val guard = state.read(state.ptrOffset + op.summary.guardOffset) ?: return false
                    if (!applyLoopSummaryConcrete(state, op.summary, Const(guard))) return false
                }

                is AnalysisConditional -> if ((state.read(state.ptrOffset + op.offset) ?: return false) != 0 && !executeSequence(op.body)) return false
                is AnalysisLoop -> while ((state.read(state.ptrOffset + op.offset) ?: return false) != 0) {
                    if (!executeSequence(op.body)) return false
                }
            }
        }
        return true
    }

    return executeSequence(ops)
}

private fun applyLoopSummaryConcrete(
    state: ConcreteState,
    summary: Optimizer.LoopSummary,
    guard: Const,
): Boolean {
    fun eval(expr: Expr, ptrOffset: Int = state.ptrOffset): Long? = when (expr) {
        is Const -> expr.value.toLong()
        is Cell -> state.read(ptrOffset + expr.offset)?.toLong()
        is GetTemp -> state.temps[expr.temp]
        is Add -> {
            var sum = 0L
            for (term in expr.terms) {
                sum += eval(term, ptrOffset) ?: return null
            }
            sum
        }

        is Mul -> {
            var product = 1L
            for (factor in expr.factors) {
                product *= eval(factor, ptrOffset) ?: return null
            }
            product
        }

        is Neg -> -(eval(expr.value, ptrOffset) ?: return null)
        is ByteTruncate -> truncateByte(eval(expr.value, ptrOffset) ?: return null).toLong()
        is ExactDiv -> exactDivide(eval(expr.numerator, ptrOffset) ?: return null, expr.divisor.toLong())
        is Choose -> chooseConst(eval(expr.value, ptrOffset) ?: return null, expr.degree)
    }

    fun applyWrites(writes: List<Optimizer.LoopWrite>): Boolean {
        val snapshot = writes.map { write ->
            val value = truncateByte(eval(write.value) ?: return false)
            (state.ptrOffset + write.offset) to value
        }
        for ((absOffset, value) in snapshot) {
            if (value == state.entry(absOffset)) state.cells.remove(absOffset) else state.cells[absOffset] = value
        }
        return true
    }

    if (summary.prologue.isNotEmpty()) {
        if (guard.value == 0) return true
        if (!applyWrites(summary.prologue)) return false
    }
    if (!applyWrites(summary.writes)) return false
    state.ptrOffset += summary.pointerDelta
    return true
}

private fun touchedOffsets(ops: List<AnalysisOp>, initialPtr: Int = 0): Set<Int> {
    var ptrOffset = initialPtr
    val touched = mutableSetOf<Int>()

    for (op in ops) {
        when (op) {
            is AnalysisStep -> when (val step = op.op) {
                is MovePtr -> ptrOffset += step.delta
                is SetTemp -> touched += step.value.readOffsets().map { ptrOffset + it }
                is Store -> {
                    touched += ptrOffset + step.offset
                    touched += step.value.readOffsets().map { ptrOffset + it }
                }
                is Read -> touched += ptrOffset + step.offset
                is Write -> touched += step.value.readOffsets().map { ptrOffset + it }
                else -> error("Unexpected control op in AnalysisStep: $step")
            }

            is AnalysisSummary -> touched += touchedOffsets(op.summary, ptrOffset)
            is AnalysisConditional -> {
                touched += ptrOffset + op.offset
                touched += touchedOffsets(op.body, ptrOffset)
            }
            is AnalysisLoop -> {
                touched += ptrOffset + op.offset
                touched += touchedOffsets(op.body, ptrOffset)
            }
        }
    }

    return touched
}

private fun touchedOffsets(summary: Optimizer.LoopSummary, initialPtr: Int = 0): Set<Int> {
    val touched = mutableSetOf<Int>()
    touched += initialPtr + summary.guardOffset
    for (write in summary.prologue + summary.writes) {
        touched += initialPtr + write.offset
        touched += write.value.readOffsets().map { initialPtr + it }
    }
    return touched
}

internal data class ControlEffect(
    val modifiedOffsets: Set<Int>,
    val pointerDelta: Int?,
)

internal fun controlEffect(ops: List<AnalysisOp>): ControlEffect {
    var ptrOffset = 0
    val modified = mutableSetOf<Int>()

    for (op in ops) {
        when (op) {
            is AnalysisStep -> when (val step = op.op) {
                is MovePtr -> ptrOffset += step.delta
                is SetTemp, is Write -> {}
                is Store -> modified += ptrOffset + step.offset
                is Read -> modified += ptrOffset + step.offset
                else -> error("Unexpected control op in AnalysisStep: $step")
            }

            is AnalysisSummary -> {
                modified += (op.summary.prologue + op.summary.writes).map { ptrOffset + it.offset }
                if (op.summary.pointerDelta != 0) {
                    return ControlEffect(modified, null)
                }
            }

            is AnalysisConditional -> {
                val nested = controlEffect(op.body)
                modified += nested.modifiedOffsets.map { ptrOffset + it }
                if (nested.pointerDelta != 0) {
                    return ControlEffect(modified, null)
                }
            }

            is AnalysisLoop -> {
                val nested = controlEffect(op.body)
                modified += nested.modifiedOffsets.map { ptrOffset + it }
                if (nested.pointerDelta != 0) {
                    return ControlEffect(modified, null)
                }
            }
        }
    }

    return ControlEffect(modified, ptrOffset)
}
