package dev.rdh.bf

import dev.rdh.bf.opt.Optimizer
import dev.rdh.bf.opt.SymbolicState
import dev.rdh.bf.opt.applyLoopSummary
import dev.rdh.bf.opt.controlEffect

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
                        nextTemp = nextTemp,
                    ) ?: error("Unexpected ]")
                    val parent = stack.lastOrNull() ?: error("Unexpected ]")
                    parent += Loop(0, body)
                }
            }
        }

        return stack.singleOrNull()?.finish(
            cellDefault = { Const(0) },
            eliminateDeadWrites = true,
            nextTemp = nextTemp,
        ) ?: error("Unclosed [")
    }

    private fun lowerLoopSummary(
        body: List<Op>,
        summary: Optimizer.LoopSummary?,
        nextTemp: () -> Temp,
    ): List<Op> = when (summary) {
        null -> listOf(Loop(0, body))
        else -> {
            val lowered = buildList {
                addAll(lowerStores(summary.prologue.map { Store(it.offset, it.value) }, nextTemp))
                addAll(lowerStores(summary.writes.map { Store(it.offset, it.value) }, nextTemp))
                if (summary.pointerDelta != 0) {
                    add(MovePtr(summary.pointerDelta))
                }
            }

            if (summary.prologue.isEmpty()) lowered else listOf(Conditional(summary.guardOffset, lowered))
        }
    }

    private fun normalize(
        ops: List<Op>,
        cellDefault: (Int) -> Expr,
        eliminateDeadWrites: Boolean,
        nextTemp: () -> Temp,
    ): List<Op> {
        if (ops.isEmpty()) return emptyList()

        val state = SymbolicState(cellDefault)
        val lowered = mutableListOf<Op>()

        fun materializePending() {
            val writes = orderWrites(
                state.writes()
                    .map { (offset, expr) -> Store(offset, stripTopByte(expr)) },
                Store::offset,
                Store::value,
            )
            lowered += lowerStores(writes, nextTemp)
            if (state.ptrOffset != 0) {
                lowered += MovePtr(state.ptrOffset)
            }
            state.materializeBoundary()
        }

        fun stopWithRaw(from: Int): List<Op> {
            materializePending()
            lowered += ops.drop(from)
            return lowered
        }

        for ((index, op) in ops.withIndex()) {
            when (op) {
                is MovePtr, is SetTemp, is Store -> if (!state.apply(op)) {
                    return stopWithRaw(index)
                }

                is Read -> {
                    val absOffset = state.ptrOffset + op.offset
                    lowered += Read(absOffset)
                    state.writeCell(absOffset, Cell(absOffset))
                }

                is Write -> {
                    val value = state.eval(op.value) ?: return stopWithRaw(index)
                    lowered += Write(stripTopByte(value))
                }

                is Conditional -> {
                    val basePtr = state.ptrOffset
                    when (state.readCellForControl(basePtr + op.offset)) {
                        Const.ZERO -> {}
                        is Const -> {
                            if (op.body.any { it !is MovePtr && it !is SetTemp && it !is Store }) {
                                return stopWithRaw(index)
                            }
                            for (inner in op.body) {
                                if (!state.apply(inner)) return stopWithRaw(index)
                            }
                        }

                        else -> {
                            val effect = controlEffect(op.body)
                            materializePending()
                            lowered += op
                            if (effect.pointerDelta != 0) {
                                lowered += ops.drop(index + 1)
                                return lowered
                            }
                            state.forgetCells(effect.modifiedOffsets)
                        }
                    }
                }

                is Loop -> {
                    val basePtr = state.ptrOffset
                    when (val guard = state.readCellForControl(basePtr + op.offset)) {
                        Const.ZERO -> {}
                        else -> {
                            val summary = Optimizer.analyzeLoop(op.body) { offset -> state.readRelativeForControl(basePtr, offset) }

                            when {
                                summary != null && summary.prologue.isEmpty() -> {
                                    if (!applyLoopSummary(state, basePtr, summary, guard)) return stopWithRaw(index)
                                }

                                summary != null && guard is Const && guard.value != 0 -> {
                                    if (!applyLoopSummary(state, basePtr, summary, guard)) return stopWithRaw(index)
                                }

                                summary != null -> {
                                    materializePending()
                                    lowered += lowerLoopSummary(op.body, summary, nextTemp)
                                    state.forgetCells((summary.prologue + summary.writes).map { it.offset }.toSet())
                                }

                                else -> {
                                    val effect = controlEffect(op.body)
                                    materializePending()
                                    lowered += op
                                    if (effect.pointerDelta != 0) {
                                        lowered += ops.drop(index + 1)
                                        return lowered
                                    }
                                    state.forgetCells(effect.modifiedOffsets)
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
        private val ops = mutableListOf<Op>()

        operator fun plusAssign(op: Op) {
            when (op) {
                is MovePtr -> appendMove(op)
                is Store -> appendStore(op)
                else -> ops += op
            }
        }

        operator fun plusAssign(ops: Iterable<Op>) {
            ops.forEach { this += it }
        }

        fun finish(
            cellDefault: (Int) -> Expr,
            eliminateDeadWrites: Boolean,
            nextTemp: () -> Temp,
        ): List<Op> = normalize(ops, cellDefault, eliminateDeadWrites, nextTemp)

        private fun appendMove(op: MovePtr) {
            if (op.delta == 0) return

            val prev = ops.lastOrNull()
            if (prev is MovePtr) {
                ops.removeLast()
                this += MovePtr(prev.delta + op.delta)
            } else {
                ops += op
            }
        }

        private fun appendStore(op: Store) {
            val prev = ops.lastOrNull()
            if (prev is Store) {
                val combined = combineStores(prev, op)
                if (combined != null) {
                    ops[ops.lastIndex] = combined
                    return
                }
            }

            ops += op
        }

        private fun combineStores(first: Store, second: Store): Store? {
            if (first.offset != second.offset) return null

            val firstDelta = first.value.additiveDelta(first.offset) ?: return null
            val secondDelta = second.value.additiveDelta(second.offset) ?: return null
            return Store(first.offset, Cell(first.offset) + Const(firstDelta + secondDelta))
        }
    }
}
