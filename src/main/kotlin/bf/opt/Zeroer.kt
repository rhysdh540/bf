package bf.opt

import bf.BFOperation
import bf.Loop
import bf.SetToZero
import bf.ValueChange
import kotlin.math.abs

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