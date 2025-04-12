package bf.opt

import bf.BFOperation
import bf.Loop
import bf.SetToZero
import bf.ValueChange
import kotlin.math.abs

/**
 * Replaces any loops that are guaranteed to be zero with a [SetToZero] operation.
 *
 * This is done by checking if the loop contains a single [ValueChange] operation
 * with a value of 1 or -1. If so, the loop is replaced with a [SetToZero] operation.
 */
internal object Zeroer : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        program.replaceAll {
            if (it is Loop && it.isZero) SetToZero else it
        }
    }

    private val Loop.isZero: Boolean
        get() {
            val op = this.singleOrNull() ?: return false
            return op is ValueChange && abs(op.value) == 1
        }
}