package bf.opt

import bf.BFOperation
import bf.Copy
import bf.Loop
import bf.PointerMove
import bf.ValueChange
import bf.defaultMap

/**
 * Optimization pass that replaces copy loops with [Copy] operations.
 *
 * A copy loop is a loop that:
 * 1. Decrements the current cell by 1 each iteration
 * 2. Moves to other cells and increments/decrements them by some amount
 * 3. Returns to the original position
 *
 * For example, the loop `[->++>>+<<]` copies the current value:
 * - 2 times to the cell at offset +1
 * - 1 time to the cell at offset +3
 *
 * This gets replaced with `Copy(mapOf(1 to 2, 3 to 1))`.
 */
internal object CopyLoopReplacer : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size) {
            val op = program[i]
            if (op is Loop) {
                val copyOp = analyzeCopyLoop(op)
                if (copyOp != null) {
                    program[i] = copyOp
                }
            }
            i++
        }
    }

    private fun analyzeCopyLoop(loop: Loop): Copy? {
        if (loop.isEmpty()) return null

        var currentOffset = 0
        val offsetChanges = defaultMap<Int, Int> { 0 }

        for (op in loop) {
            when (op) {
                is PointerMove -> currentOffset += op.value
                is ValueChange -> {
                    val targetOffset = currentOffset + op.offset
                    offsetChanges[targetOffset] += op.value
                }
                else -> return null
            }
        }

        // Must return to original position
        if (currentOffset != 0) return null

        val currentCellChange = offsetChanges.remove(0)
        if (currentCellChange != -1) return null

        val multipliers = mutableMapOf<Int, Int>()
        for ((offset, change) in offsetChanges) {
            multipliers[offset] = change
        }

        // Must have at least one target to copy to
        if (multipliers.isEmpty()) return null

        return Copy(multipliers)
    }
}
