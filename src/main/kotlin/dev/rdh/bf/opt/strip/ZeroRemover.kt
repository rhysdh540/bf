package dev.rdh.bf.opt.strip

import dev.rdh.bf.BFOperation
import dev.rdh.bf.PointerMove
import dev.rdh.bf.ValueChange
import dev.rdh.bf.opt.OptimisationPass

/**
 * Removes [ValueChange] and [PointerMove] operations that have a value of 0.
 */
object ZeroRemover : OptimisationPass {
    override fun run(program: MutableList<BFOperation>) {
        program.removeAll {
            it is ValueChange && it.value == 0
                    || it is PointerMove && it.value == 0
        }
    }
}