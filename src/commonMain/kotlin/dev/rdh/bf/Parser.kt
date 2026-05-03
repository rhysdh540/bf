package dev.rdh.bf

object Parser {
    fun parse(program: CharSequence): List<Op> {
        val stack = mutableListOf(RegionBuilder())

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
                    val body = stack.removeLastOrNull()?.finish() ?: error("Unexpected ]")
                    val parent = stack.lastOrNull() ?: error("Unexpected ]")
                    parent += optimizeClosedLoop(body)
                }
            }
        }

        return stack.singleOrNull()?.finish() ?: error("Unclosed [")
    }

    private fun optimizeClosedLoop(body: List<Op>): List<Op> {
        if (body.isEmpty()) return emptyList()
        return listOf(Loop(0, body))
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

        fun finish(): List<Op> = ops.toList()

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

            val firstDelta = additiveDelta(first.value, first.offset) ?: return null
            val secondDelta = additiveDelta(second.value, second.offset) ?: return null
            return Store(first.offset, Cell(first.offset) + Const(firstDelta + secondDelta))
        }

        private fun additiveDelta(expr: Expr, offset: Int): Int? = when (expr) {
            is Add -> {
                if (expr.terms.size != 2) return null
                val cell = expr.terms.firstOrNull { it == Cell(offset) }
                val constant = expr.terms.firstOrNull { it is Const } as? Const
                if (cell == null || constant == null) null else constant.value
            }
            else -> null
        }
    }
}
