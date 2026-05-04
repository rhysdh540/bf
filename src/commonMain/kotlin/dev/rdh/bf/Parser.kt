package dev.rdh.bf

import dev.rdh.bf.opt.AnalysisConditional
import dev.rdh.bf.opt.AnalysisLoop
import dev.rdh.bf.opt.AnalysisOp
import dev.rdh.bf.opt.AnalysisStep
import dev.rdh.bf.opt.AnalysisSummary
import dev.rdh.bf.opt.Optimizer
import dev.rdh.bf.opt.SymbolicState
import dev.rdh.bf.opt.applyLoopSummary
import dev.rdh.bf.opt.controlEffect
import dev.rdh.bf.opt.toAnalysisOp
import dev.rdh.bf.opt.validateLoopSummary

object Parser {
    fun parse(program: CharSequence): List<Op> {
        val stack = mutableListOf(RegionBuilder())
        val nextTemp = generateSequence(0) { it + 1 }.map(::Temp).iterator()::next

        for (c in program) {
            when (c) {
                '>' -> stack.last() += MovePtr(1)
                '<' -> stack.last() += MovePtr(-1)
                '+' -> stack.last() += Store(0, Cell(0) + Const(1))
                '-' -> stack.last() += Store(0, Cell(0) + Const(-1))
                '.' -> stack.last() += Write(Cell(0))
                ',' -> stack.last() += Read(0)
                '[' -> stack += RegionBuilder()
                ']' -> {
                    val body = stack.removeLastOrNull()?.finish(
                        cellDefault = { Cell(it) },
                        eliminateDeadWrites = false,
                        analyzeLoops = false,
                        nextTemp = nextTemp,
                    ) ?: error("Unexpected ]")
                    val parent = stack.lastOrNull() ?: error("Unexpected ]")
                    parent += AnalysisLoop(0, body)
                }
            }
        }

        val ops = stack.singleOrNull()?.finish(
            cellDefault = { Const(0) },
            eliminateDeadWrites = true,
            analyzeLoops = true,
            nextTemp = nextTemp,
        ) ?: error("Unclosed [")
        return lowerAnalysisOps(ops, nextTemp)
    }

    private fun lowerLoopSummary(
        summary: Optimizer.LoopSummary,
        nextTemp: () -> Temp,
    ): List<Op> {
        val lowered = buildList {
            addAll(lowerStores(summary.prologue.map { Store(it.offset, it.value) }, nextTemp))
            addAll(lowerStores(summary.writes.map { Store(it.offset, it.value) }, nextTemp))
            if (summary.pointerDelta != 0) {
                add(MovePtr(summary.pointerDelta))
            }
        }
        return if (summary.prologue.isEmpty()) lowered else listOf(Conditional(summary.guardOffset, lowered))
    }

    private fun lowerAnalysisOps(
        ops: List<AnalysisOp>,
        nextTemp: () -> Temp,
    ): List<Op> = buildList {
        for (op in ops) {
            when (op) {
                is AnalysisStep -> add(op.op)
                is AnalysisConditional -> add(Conditional(op.offset, lowerAnalysisOps(op.body, nextTemp)))
                is AnalysisLoop -> add(Loop(op.offset, lowerAnalysisOps(op.body, nextTemp)))
                is AnalysisSummary -> addAll(lowerLoopSummary(op.summary, nextTemp))
            }
        }
    }

    private fun normalize(
        ops: List<AnalysisOp>,
        cellDefault: (Int) -> Expr,
        eliminateDeadWrites: Boolean,
        analyzeLoops: Boolean,
        nextTemp: () -> Temp,
    ): List<AnalysisOp> {
        if (ops.isEmpty()) return emptyList()

        val state = SymbolicState(cellDefault)
        val lowered = mutableListOf<AnalysisOp>()

        fun emit(op: Op) {
            lowered += op.toAnalysisOp()
        }

        fun emit(analysisOp: AnalysisOp) {
            lowered += analysisOp
        }

        fun emitAll(ops: Iterable<Op>) {
            ops.forEach(::emit)
        }

        fun materializePending() {
            val writes = orderWrites(
                state.writes()
                    .map { (offset, expr) -> Store(offset, stripTopByte(expr)) },
                Store::offset,
                Store::value,
            )
            emitAll(lowerStores(writes, nextTemp))
            if (state.ptrOffset != 0) {
                emit(MovePtr(state.ptrOffset))
            }
            state.materializeBoundary()
        }

        fun stopWithRaw(from: Int): List<AnalysisOp> {
            materializePending()
            lowered += ops.drop(from)
            return lowered
        }

        fun prepareForWrites(offsets: Iterable<Int>) {
            val absoluteOffsets = offsets.map { state.ptrOffset + it }
            if (state.hasExternalReads(absoluteOffsets)) {
                materializePending()
                state.clearFacts()
            }
        }

        for ((index, op) in ops.withIndex()) {
            when (op) {
                is AnalysisStep -> when (val step = op.op) {
                    is MovePtr, is SetTemp, is Store -> if (!state.apply(step)) {
                        return stopWithRaw(index)
                    }

                    is Read -> {
                        prepareForWrites(listOf(step.offset))
                        val absOffset = state.ptrOffset + step.offset
                        emit(Read(absOffset))
                        state.forgetCells(listOf(absOffset))
                        state.writeCell(absOffset, Cell(absOffset))
                    }

                    is Write -> {
                        val value = state.eval(step.value) ?: return stopWithRaw(index)
                        emit(Write(stripTopByte(value)))
                    }

                    else -> error("Unexpected control op in AnalysisStep: $step")
                }

                is AnalysisSummary -> {
                    val basePtr = state.ptrOffset
                    val guard = state.readControl(basePtr + op.summary.guardOffset)
                    if (guard.expr is Const && validateLoopSummary(op.body, op.summary) { offset -> state.readCell(basePtr + offset) } == false) {
                        val effect = controlEffect(op.body)
                        materializePending()
                        emit(AnalysisLoop(op.summary.guardOffset, op.body))
                        if (effect.pointerDelta != 0) {
                            lowered += ops.drop(index + 1)
                            return lowered
                        }
                        state.clearFacts()
                        continue
                    }
                    if (applyLoopSummary(state, basePtr, op.summary, guard)) {
                        continue
                    }

                    materializePending()
                    emit(op)
                    if (op.summary.pointerDelta != 0) {
                        lowered += ops.drop(index + 1)
                        return lowered
                    }
                    state.clearFacts()
                }

                is AnalysisConditional -> {
                    if (!analyzeLoops) {
                        val effect = controlEffect(op.body)
                        materializePending()
                        emit(op)
                        if (effect.pointerDelta != 0) {
                            lowered += ops.drop(index + 1)
                            return lowered
                        }
                        state.clearFacts()
                        continue
                    }

                    val basePtr = state.ptrOffset
                    val guard = state.readControl(basePtr + op.offset)
                    when {
                        guard.isZero -> {}
                        guard.definitelyNonZero -> {
                            for (inner in op.body) {
                                when (inner) {
                                    is AnalysisStep -> {
                                        val innerStep = inner.op
                                        if (innerStep !is MovePtr && innerStep !is SetTemp && innerStep !is Store) {
                                            return stopWithRaw(index)
                                        }
                                        if (!state.apply(innerStep)) return stopWithRaw(index)
                                    }

                                    is AnalysisSummary -> {
                                        val innerGuard = state.readControl(state.ptrOffset + inner.summary.guardOffset)
                                        if (!applyLoopSummary(state, state.ptrOffset, inner.summary, innerGuard)) {
                                            return stopWithRaw(index)
                                        }
                                    }

                                    else -> return stopWithRaw(index)
                                }
                            }
                        }

                        else -> {
                            val effect = controlEffect(op.body)
                            materializePending()
                            emit(op)
                            if (effect.pointerDelta != 0) {
                                lowered += ops.drop(index + 1)
                                return lowered
                            }
                            state.clearFacts()
                        }
                    }
                }

                is AnalysisLoop -> {
                    if (!analyzeLoops) {
                        val effect = controlEffect(op.body)
                        val summary = Optimizer.analyzeLoopAnalysis(op.body)
                        val isLeafLoop = op.body.all { it is AnalysisStep }
                        materializePending()
                        when {
                            summary != null && summary.prologue.isEmpty() && isLeafLoop -> emit(AnalysisSummary(op.body, summary))
                            else -> emit(op)
                        }
                        if (effect.pointerDelta != 0) {
                            lowered += ops.drop(index + 1)
                            return lowered
                        }
                        state.clearFacts()
                        continue
                    }

                    val basePtr = state.ptrOffset
                    val guard = state.readControl(basePtr + op.offset)
                    when {
                        guard.isZero -> {}
                        else -> {
                            val summary = Optimizer.analyzeLoopAnalysis(
                                body = op.body,
                                readCell = { offset -> state.readRelative(basePtr, offset) },
                                knownNonZeroOffsets = state.knownRelativeNonZeroOffsets(basePtr) + op.offset,
                            )
                                ?.takeIf { candidate ->
                                    guard.expr !is Const || validateLoopSummary(op.body, candidate) { offset ->
                                        state.readCell(basePtr + offset)
                                    } != false
                                }

                            when {
                                summary != null && summary.prologue.isEmpty() -> {
                                    if (!applyLoopSummary(state, basePtr, summary, guard)) return stopWithRaw(index)
                                }

                                summary != null && guard.definitelyNonZero -> {
                                    if (!applyLoopSummary(state, basePtr, summary, guard)) return stopWithRaw(index)
                                }

                                summary != null -> {
                                    materializePending()
                                    emit(AnalysisSummary(op.body, summary))
                                    state.clearFacts()
                                }

                                else -> {
                                    val effect = controlEffect(op.body)
                                    materializePending()
                                    emit(op)
                                    if (effect.pointerDelta != 0) {
                                        lowered += ops.drop(index + 1)
                                        return lowered
                                    }
                                    state.clearFacts()
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!eliminateDeadWrites) {
            materializePending()
        }

        return lowered
    }

    private fun lowerStores(stores: List<Store>, nextTemp: () -> Temp): List<Op> {
        if (stores.isEmpty()) return emptyList()

        val writePos = stores.withIndex().associate { (index, store) -> store.offset to index }
        val lastReadPos = mutableMapOf<Int, Int>()

        for ((index, store) in stores.withIndex()) {
            for (offset in store.value.readOffsets()) {
                if (offset in writePos) {
                    lastReadPos[offset] = maxOf(lastReadPos[offset] ?: -1, index)
                }
            }
        }

        val snapshots = writePos.keys
            .filter { offset -> writePos.getValue(offset) < (lastReadPos[offset] ?: -1) }
            .sorted()
            .associateWith { nextTemp() }

        return buildList {
            for ((offset, temp) in snapshots) {
                add(SetTemp(temp, Cell(offset)))
            }
            for (store in stores) {
                add(Store(store.offset, stripTopByte(substituteCells(store.value, snapshots))))
            }
        }
    }

    private fun substituteCells(expr: Expr, snapshots: Map<Int, Temp>): Expr = when (expr) {
        is Const, is GetTemp -> expr
        is Cell -> snapshots[expr.offset]?.let(::GetTemp) ?: expr
        is Add -> expr.terms.fold(Const.ZERO as Expr) { acc, term -> acc + substituteCells(term, snapshots) }
        is Mul -> expr.factors.fold(Const.ONE as Expr) { acc, factor -> acc * substituteCells(factor, snapshots) }
        is Neg -> -substituteCells(expr.value, snapshots)
        is ByteTruncate -> byte(substituteCells(expr.value, snapshots))
        is ExactDiv -> substituteCells(expr.numerator, snapshots) / expr.divisor
        is Choose -> choose(substituteCells(expr.value, snapshots), expr.degree)
    }

    private class RegionBuilder {
        private val ops = mutableListOf<AnalysisOp>()

        operator fun plusAssign(op: Op) {
            this += op.toAnalysisOp()
        }

        operator fun plusAssign(op: AnalysisOp) {
            when (op) {
                is AnalysisStep -> when (val step = op.op) {
                    is MovePtr -> appendMove(step)
                    is Store -> appendStore(step)
                    else -> ops += op
                }

                else -> ops += op
            }
        }

        operator fun plusAssign(ops: Iterable<AnalysisOp>) {
            ops.forEach { this += it }
        }

        fun finish(
            cellDefault: (Int) -> Expr,
            eliminateDeadWrites: Boolean,
            analyzeLoops: Boolean,
            nextTemp: () -> Temp,
        ): List<AnalysisOp> {
            var current = ops.toList()
            while (true) {
                val normalized = normalize(current, cellDefault, eliminateDeadWrites, analyzeLoops, nextTemp)
                if (normalized == current) return normalized
                current = normalized
            }
        }

        private fun appendMove(op: MovePtr) {
            if (op.delta == 0) return

            val prev = (ops.lastOrNull() as? AnalysisStep)?.op as? MovePtr
            if (prev != null) {
                ops.removeLast()
                this += MovePtr(prev.delta + op.delta)
            } else {
                ops += AnalysisStep(op)
            }
        }

        private fun appendStore(op: Store) {
            val prev = (ops.lastOrNull() as? AnalysisStep)?.op as? Store
            if (prev != null) {
                val combined = combineStores(prev, op)
                if (combined != null) {
                    ops[ops.lastIndex] = AnalysisStep(combined)
                    return
                }
            }

            ops += AnalysisStep(op)
        }

        private fun combineStores(first: Store, second: Store): Store? {
            if (first.offset != second.offset) return null

            val firstDelta = first.value.additiveDelta(first.offset) ?: return null
            val secondDelta = second.value.additiveDelta(second.offset) ?: return null
            return Store(first.offset, Cell(first.offset) + Const(firstDelta + secondDelta))
        }
    }
}
