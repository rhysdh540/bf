package dev.rdh.bf.opt

import dev.rdh.bf.*

internal class SymbolicState(
    private var cellDefault: (Int) -> Expr,
    private val exactCells: Boolean = true,
) {
    private val state = mutableMapOf<Int, Expr>()
    private val temps = mutableMapOf<Temp, Expr>()

    var ptrOffset: Int = 0
        private set

    fun readCell(absOffset: Int): Expr = state[absOffset] ?: cellDefault(absOffset)

    fun readCellForControl(absOffset: Int): Expr = when (val value = readCell(absOffset)) {
        is Cell -> value
        else -> value.constantValue()?.let(::truncateByte)?.let(::Const) ?: Cell(absOffset)
    }

    fun readTemp(temp: Temp): Expr? = temps[temp]

    fun eval(expr: Expr, ptrOffset: Int = this.ptrOffset): Expr? =
        substitute(expr, ptrOffset, ::readCell, ::readTemp)

    fun readRelative(basePtr: Int, offset: Int): Expr = shiftExpr(readCell(basePtr + offset), -basePtr)

    fun readRelativeForControl(basePtr: Int, offset: Int): Expr = shiftExpr(readCellForControl(basePtr + offset), -basePtr)

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

    fun forgetCells(offsets: Iterable<Int>) {
        for (offset in offsets) {
            state.remove(offset)
        }
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

        ptrOffset = 0
        cellDefault = { Cell(it) }
    }

    fun writeCell(absOffset: Int, value: Expr) {
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
    }
}
