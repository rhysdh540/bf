package dev.rdh.bf

import dev.rdh.bf.scev.Optimizer

object Parser {
    fun parse(program: CharSequence): List<Op> {
        val stack = mutableListOf(RegionBuilder())
        var nextTempId = 0
        val nextTemp = { Temp(nextTempId++) }

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
                    parent += lowerLoopSummary(body, Optimizer.analyzeLoop(body), nextTemp)
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

        var ptrOffset = 0
        val state = mutableMapOf<Int, Expr>()
        val temps = mutableMapOf<Temp, Expr>()
        val ioOps = mutableListOf<Op>()

        fun readCell(absOffset: Int): Expr = state[absOffset] ?: cellDefault(absOffset)
        fun readTemp(temp: Temp): Expr? = temps[temp]

        fun execute(sequence: List<Op>): Boolean {
            for (op in sequence) {
                when (op) {
                    is MovePtr -> ptrOffset += op.delta
                    is SetTemp -> {
                        temps[op.temp] = substitute(op.value, ptrOffset, ::readCell, ::readTemp) ?: return false
                    }

                    is Store -> {
                        val absOffset = ptrOffset + op.offset
                        state[absOffset] = substitute(op.value, ptrOffset, ::readCell, ::readTemp) ?: return false
                    }

                    is Read -> {
                        val absOffset = ptrOffset + op.offset
                        ioOps += Read(absOffset)
                        state[absOffset] = Cell(absOffset)
                    }

                    is Write -> {
                        ioOps += Write(substitute(op.value, ptrOffset, ::readCell, ::readTemp) ?: return false)
                    }

                    is Conditional -> {
                        val guard = readCell(ptrOffset + op.offset)
                        when (guard) {
                            Const(0) -> {}
                            is Const -> if (!execute(op.body)) return false
                            else -> return false
                        }
                    }

                    is Loop -> {
                        val guard = readCell(ptrOffset + op.offset)
                        when (guard) {
                            Const(0) -> {}
                            else -> return false
                        }
                    }
                }
            }

            return true
        }

        if (!execute(ops)) return ops
        if (eliminateDeadWrites) return ioOps

        val writes = orderWrites(
            state.entries
                .filter { (offset, expr) -> expr != cellDefault(offset) }
                .map { (offset, expr) -> Store(offset, expr) },
            Store::offset,
            Store::value,
        )

        return buildList {
            addAll(ioOps)
            addAll(lowerStores(writes, nextTemp))
            if (ptrOffset != 0) add(MovePtr(ptrOffset))
        }
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
                add(Store(store.offset, substituteCells(store.value, snapshots)))
            }
        }
    }

    private fun substituteCells(expr: Expr, snapshots: Map<Int, Temp>): Expr = when (expr) {
        is Const -> expr
        is Cell -> snapshots[expr.offset]?.let(::GetTemp) ?: expr
        is GetTemp -> expr
        is Add -> add(*expr.terms.map { substituteCells(it, snapshots) }.toTypedArray())
        is Mul -> mul(*expr.factors.map { substituteCells(it, snapshots) }.toTypedArray())
        is Neg -> neg(substituteCells(expr.value, snapshots))
        is ExactDiv -> ExactDiv(substituteCells(expr.numerator, snapshots), expr.divisor)
        is Choose -> Choose(substituteCells(expr.value, snapshots), expr.degree)
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
