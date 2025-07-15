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
        program.replaceAll {
            if (it !is Loop) {
                return@replaceAll it
            }

            val newProgram = it.contents.toMutableList()
            passes.forEach { p ->
                p.run(newProgram)
            }

            this.run(newProgram) // go deeper

            Loop(newProgram)
        }
    }
}