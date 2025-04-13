package bf.opt

import bf.BFOperation
import bf.Loop
import bf.SetToConstant
import bf.ValueChange
import kotlin.math.abs

/**
 * Replaces some loops with a [SetToConstant] operation.
 *
 * This is done by checking if the loop contains a single [ValueChange] operation
 * with a value of 1 or -1. If so, the loop is replaced with a [SetToConstant] operation,
 * where the value is set to 0.
 *
 * If the next operation is a [ValueChange], it is inlined into the [SetToConstant] operation.
 */
internal object ConstantReplacer : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        var i = 0
        while (i < program.size) {
            val op = program[i]
            if (op is Loop && op.isZeroReplaceable) {
                program[i] = SetToConstant()

                if (i != program.lastIndex && program[i + 1] is ValueChange) {
                    // If the next operation is a ValueChange, we can inline it
                    val op2 = program[i + 1] as ValueChange
                    program[i + 1] = SetToConstant(op2.value.toUByte())
                }
            }

            i++
        }
    }

    private val Loop.isZeroReplaceable: Boolean
        get() {
            val op = this.singleOrNull() ?: return false
            return op is ValueChange && abs(op.value) == 1
        }
}