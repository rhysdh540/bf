package dev.rdh.bf.opt.strip

import dev.rdh.bf.BFOperation
import dev.rdh.bf.Copy
import dev.rdh.bf.Loop
import dev.rdh.bf.PointerMove
import dev.rdh.bf.SetToConstant
import dev.rdh.bf.ValueChange
import dev.rdh.bf.opt.OptimisationPass

/**
 * Optimisation pass that removes dead code at the start of the program.
 * Dead code is guaranteed to never run.
 * This includes loops and set-to-constant operations.
 */
internal object DeadStartRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        while (program.first().valid) {
            program.removeFirst()
        }
    }

    private val BFOperation.valid: Boolean
        get() {
            if (this is Loop) return true
            if (this is SetToConstant && this.value.toInt() == 0 && this.offset == 0) return true
            if (this is Copy) return true
            return false
        }
}

/**
 * Optimisation pass that removes necessary code at the end of the program.
 * Unnecessary code may change the internal state of the program but will have
 * no effect on the final output.
 * This includes pointer moves, value changes, set-to-constant operations, and copy operations.
 */
internal object DeadEndRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        while (program.isNotEmpty() && program.last().valid) {
            program.removeAt(program.lastIndex)
        }
    }

    private val BFOperation.valid: Boolean
        get() = this is PointerMove || this is ValueChange || this is SetToConstant || this is Copy
}