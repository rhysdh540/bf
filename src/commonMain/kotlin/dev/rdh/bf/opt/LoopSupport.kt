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

internal data class ControlEffect(
    val modifiedOffsets: Set<Int>,
    val pointerDelta: Int?,
)

internal fun controlEffect(ops: List<Op>): ControlEffect {
    var ptrOffset = 0
    val modified = mutableSetOf<Int>()

    for (op in ops) {
        when (op) {
            is MovePtr -> ptrOffset += op.delta
            is SetTemp, is Write -> {}
            is Store -> modified += ptrOffset + op.offset
            is Read -> modified += ptrOffset + op.offset
            is Conditional -> {
                val nested = controlEffect(op.body)
                modified += nested.modifiedOffsets.map { ptrOffset + it }
                if (nested.pointerDelta != 0) {
                    return ControlEffect(modified, null)
                }
            }

            is Loop -> {
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
