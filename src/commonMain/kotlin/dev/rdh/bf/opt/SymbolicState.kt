package dev.rdh.bf.opt

import dev.rdh.bf.*

internal data class ControlValue(
    val expr: Expr,
    val definitelyNonZero: Boolean,
) {
    val isZero: Boolean
        get() = expr == Const.ZERO
}

internal class SymbolicState(
    private var cellDefault: (Int) -> Expr,
    private val exactCells: Boolean = true,
    knownNonZeroOffsets: Set<Int> = emptySet(),
) {
    private val state = mutableMapOf<Int, Expr>()
    private val temps = mutableMapOf<Temp, Expr>()
    private val nonZero = knownNonZeroOffsets.toMutableSet()

    var ptrOffset: Int = 0
        private set

    fun readCell(absOffset: Int): Expr = state[absOffset] ?: cellDefault(absOffset)

    fun readControl(absOffset: Int): ControlValue = when (val value = readCell(absOffset)) {
        is Cell -> ControlValue(value, absOffset in nonZero)
        else -> {
            when (val constant = value.constantValue()?.let(::truncateByte)) {
                null -> ControlValue(Cell(absOffset), absOffset in nonZero)
                0 -> ControlValue(Const.ZERO, false)
                else -> ControlValue(Const(constant), true)
            }
        }
    }

    fun readTemp(temp: Temp): Expr? = temps[temp]

    fun eval(expr: Expr, ptrOffset: Int = this.ptrOffset): Expr? =
        substitute(expr, ptrOffset, ::readCell, ::readTemp)

    fun readRelative(basePtr: Int, offset: Int): Expr = shiftExpr(readCell(basePtr + offset), -basePtr)

    fun knownRelativeNonZeroOffsets(basePtr: Int): Set<Int> = nonZero.mapTo(mutableSetOf()) { it - basePtr }

    fun move(delta: Int) {
        ptrOffset += delta
    }

    fun apply(op: Op): Boolean = when (op) {
        is MovePtr -> {
            move(op.delta)
            true
        }

        is SetTemp -> {
            temps[op.temp] = eval(op.value) ?: return false
            true
        }

        is Store -> {
            val absOffset = ptrOffset + op.offset
            writeCell(absOffset, eval(op.value) ?: return false)
            true
        }

        else -> false
    }

    fun applyWrites(basePtr: Int, writes: List<Pair<Int, Expr>>): Boolean {
        val updates = mutableListOf<Pair<Int, Expr>>()
        for ((offset, value) in writes) {
            val evaluated = substitute(value, basePtr, ::readCell, ::readTemp) ?: return false
            updates += (basePtr + offset) to evaluated
        }
        for ((absOffset, value) in updates) {
            writeCell(absOffset, value)
        }
        return true
    }

    fun writes(): Map<Int, Expr> = state.toMap()

    fun hasExternalReads(offsets: Iterable<Int>): Boolean {
        val targetSet = offsets.toSet()
        if (targetSet.isEmpty()) return false

        if (temps.values.any { expr -> expr.readOffsets().any { it in targetSet } }) {
            return true
        }

        return state.any { (offset, expr) ->
            offset !in targetSet && expr.readOffsets().any { it in targetSet }
        }
    }

    fun forgetCells(offsets: Iterable<Int>) {
        for (offset in offsets) {
            state.remove(offset)
            nonZero.remove(offset)
        }
    }

    fun clearFacts() {
        state.clear()
        temps.clear()
        nonZero.clear()
    }

    fun materializeBoundary() {
        val shift = ptrOffset
        val rebasedState = state.mapKeys { (offset, _) -> offset - shift }
            .mapValues { (_, value) -> shiftExpr(value, -shift) }
        val rebasedTemps = temps.mapValues { (_, value) -> shiftExpr(value, -shift) }

        state.clear()
        for ((offset, value) in rebasedState) {
            if (value != Cell(offset)) {
                state[offset] = value
            }
        }

        temps.clear()
        temps.putAll(rebasedTemps)

        val rebasedNonZero = nonZero.mapTo(mutableSetOf()) { it - shift }
        nonZero.clear()
        nonZero += rebasedNonZero

        ptrOffset = 0
        cellDefault = { Cell(it) }
    }

    fun writeCell(absOffset: Int, value: Expr) {
        val previous = readCell(absOffset)
        val wasNonZero = absOffset in nonZero
        val normalized = if (exactCells) {
            byte(value)
        } else {
            relaxByte(value).truncateKnownByte()
        }
        if (normalized == cellDefault(absOffset)) {
            state.remove(absOffset)
        } else {
            state[absOffset] = normalized
        }

        when (normalized.constantValue()?.let(::truncateByte)) {
            null -> if (definitelyNonZero(normalized) || (wasNonZero && normalized == previous)) {
                nonZero += absOffset
            } else {
                nonZero.remove(absOffset)
            }

            0 -> nonZero.remove(absOffset)
            else -> nonZero += absOffset
        }
    }

    private fun definitelyNonZero(expr: Expr): Boolean = when (expr) {
        is Const -> truncateByte(expr.value.toLong()) != 0
        is Cell -> expr.offset in nonZero
        is GetTemp -> false
        is Neg -> definitelyNonZero(expr.value)
        is Mul -> {
            val nonConstFactors = expr.factors.filter { it !is Const }
            if (nonConstFactors.size != 1) return false

            val factor = nonConstFactors.single()
            val constant = expr.factors
                .filterIsInstance<Const>()
                .fold(1) { acc, value -> truncateByte((acc * value.value).toLong()) }
            truncateByte(constant.toLong()) % 2 == 1 && definitelyNonZero(factor)
        }

        else -> false
    }
}
