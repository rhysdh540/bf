package bf.opt

import bf.BFOperation
import bf.Loop

/**
 * Optimisation pass that runs a set of other passes on the contents of loops.
 */
internal class LoopOptimiser(vararg val passes: OptimisationPass) : OptimisationPass {
    init {
        if (passes.any { it is LoopOptimiser }) {
            throw IllegalArgumentException("LoopOptimiser cannot contain another LoopOptimiser")
        }
    }

    override fun run(program: MutableList<BFOperation>) {
        for (i in program.indices) {
            val op = program[i]
            if (op is Loop) {
                val newContents = op.toMutableList()
                passes.forEach { it.run(newContents) }
                this.run(newContents) // go deeper
                program[i] = Loop(newContents)
            }
        }
    }
}