@file:JvmName("BrainfuckOptimisation")

package dev.rdh.bf.opt

import dev.rdh.bf.BFOperation
import dev.rdh.bf.opt.strip.ConsecutiveLoopRemover
import dev.rdh.bf.opt.strip.DeadEndRemover
import dev.rdh.bf.opt.strip.DeadStartRemover
import dev.rdh.bf.opt.strip.ZeroRemover


/**
 * Represents some form of modification to a program. Any optimisations should NOT change the behavior of the program.
 */
internal interface OptimisationPass {

    /**
     * Modifies the given list of operations.
     */
    fun run(program: MutableList<BFOperation>)
}

@JvmName("optimise")
fun bfOptimise(program: Iterable<BFOperation>): List<BFOperation> {
    val program = program.toMutableList()

    val passes = arrayOf(
        RunLengthMerger, ConstantReplacer, OffsetAdder, CopyLoopReplacer
    )

    passes.forEach {
        it.run(program)
    }

    LoopOptimiser(*passes).run(program)

    return program
}

/**
 * Removes unnecessary operations from a Brainfuck program.
 * This will:
 * - remove any [bf.PointerMove] or [bf.ValueChange] operations that have `value == 0`
 * - remove any loops at the beginning of the program, which will never run
 * - remove any [bf.PointerMove], [bf.ValueChange] or [bf.SetToConstant] operations at the end of the program
 * - removes the second and after of any set of consecutive loops
 */
@JvmName("strip")
fun bfStrip(program: Iterable<BFOperation>): List<BFOperation> {
    val program = program.toMutableList()

    val passes = arrayOf(
        DeadStartRemover,
        DeadEndRemover,
        ConsecutiveLoopRemover,
        ZeroRemover
    )

    passes.forEach {
        it.run(program)
    }

    LoopOptimiser(ConsecutiveLoopRemover, ZeroRemover).run(program)

    return program
}