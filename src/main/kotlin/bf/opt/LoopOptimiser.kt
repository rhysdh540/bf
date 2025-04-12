package bf.opt

import bf.BFOperation
import bf.Loop

internal class LoopOptimiser(vararg val passes: OptimisationPass) : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        program.replaceAll {
            if (it is Loop) {
                val newProgram = it.contents.toMutableList()
                passes.forEach { pass ->
                    pass.run(newProgram)
                }

                this.run(newProgram) // go deeper

                Loop(newProgram)
            } else {
                it
            }
        }
    }
}