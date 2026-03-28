package dev.rdh.bf

import dev.rdh.bf.util.associateWithGuarantee
import dev.rdh.bf.util.defaultMapOf
import kotlin.text.iterator

object Parser {
    fun parse(program: CharSequence): List<BfBlockOp> {
        val opsStack = mutableListOf(mutableListOf<BfBlockOp>())
        val blockStarts = mutableListOf(0)
        val loopOpenIndices = mutableListOf<Int>()

        for (i in 0 until program.length) {
            when (program[i]) {
                '[' -> {
                    appendBlocks(opsStack.last(), parseBlock(program.subSequence(blockStarts.last(), i)))
                    opsStack.add(mutableListOf())
                    blockStarts.add(i + 1)
                    loopOpenIndices.add(i)
                }

                ']' -> {
                    if (loopOpenIndices.isEmpty()) {
                        error("Unmatched ']' at index $i")
                    }

                    appendBlocks(opsStack.last(), parseBlock(program.subSequence(blockStarts.last(), i)))
                    blockStarts.removeLast()
                    loopOpenIndices.removeLast()
                    val loopBody = opsStack.removeLast()

                    val list = opsStack.last()
                    if ((opsStack.size != 1 || list.isNotEmpty()) && list.lastOrNull() !is Loop && list.lastOrNull() !is Conditional) {
                        when (val solved = trySolveLoop(loopBody)) {
                            is WriteBlock, is Conditional -> appendBlocks(list, listOf(solved))
                            is Loop -> list += solved
                            is IOBlock, null -> list += Loop(loopBody)
                        }
                    }

                    blockStarts[blockStarts.lastIndex] = i + 1
                }
            }
        }

        if (loopOpenIndices.isNotEmpty()) {
            val i = loopOpenIndices.last()
            error("Unmatched '[' at index $i")
        }

        val out = opsStack.single()
        appendBlocks(out, parseBlock(program.subSequence(blockStarts.single(), program.length)))
        return mergeBlocks(out, cellDefault = { Expression.ZERO }, eliminateDeadWrites = true)
    }

    private fun appendBlocks(ops: MutableList<BfBlockOp>, newBlocks: List<BfBlockOp>) {
        val filtered = newBlocks.filter {
            !(it is WriteBlock && it.writes.isEmpty() && it.pointerDelta == 0)
        }
        if (filtered.isEmpty()) return

        val prev = ops.lastOrNull()
        if (prev != null && prev !is Loop) {
            val merged = mergeBlocks(listOf(prev) + filtered)
            ops.removeLast()
            ops.addAll(merged)
        } else {
            ops.addAll(filtered)
        }
    }

    private fun parseBlock(block: CharSequence): List<BfBlockOp> {
        var offset = 0
        val result = mutableListOf<BfBlockOp>()
        val writeDeltas = defaultMapOf<Int, Int> { 0 }

        fun flushWrites() {
            if (writeDeltas.isNotEmpty()) {
                val writes = writeDeltas.filterValues { it != 0 }.map { (targetOffset, delta) ->
                    Write(targetOffset, Expression(constant = delta, listOf(Expression.Term(1, listOf(targetOffset)))))
                }
                if (writes.isNotEmpty()) {
                    result += WriteBlock(0, writes, 0)
                }
                writeDeltas.clear()
            }
        }

        fun appendIO(op: BfIOOp) {
            val last = result.lastOrNull()
            if (last is IOBlock) {
                result[result.lastIndex] = IOBlock(0, last.ops + op)
            } else {
                result += IOBlock(0, listOf(op))
            }
        }

        for (c in block) {
            when (c) {
                '>' -> offset++
                '<' -> offset--
                '.' -> {
                    flushWrites()
                    appendIO(Output(Expression.cell(offset)))
                }
                ',' -> {
                    flushWrites()
                    appendIO(Input(offset))
                }
                '+' -> writeDeltas[offset]++
                '-' -> writeDeltas[offset]--
                '[', ']' -> error("[] found in no-loop block: `$block`")
            }
        }

        flushWrites()

        // put pointer delta on the last block
        if (offset != 0) {
            if (result.isNotEmpty()) {
                val last = result.last()
                result[result.lastIndex] = when (last) {
                    is WriteBlock -> last.copy(pointerDelta = offset)
                    is IOBlock -> last.copy(pointerDelta = offset)
                    else -> error("unexpected block type in parseBlock result")
                }
            } else {
                result += WriteBlock(offset, emptyList(), 0)
            }
        }

        return result
    }

    // region loop solving

    private class LoopAnalysis(
        val merged: WriteBlock,
        val writes: Map<Int, Write>,
        val deltas: Map<Int, Expression>,
        val inv: Int,
        val iterations: Expression
    )

    private fun analyze(body: List<BfBlockOp>): LoopAnalysis? {
        if (body.any { it !is WriteBlock && it !is Conditional }) return null

        val merged = mergeBlocks(body)
        if (merged.size != 1) return null
        val writeBlock = merged.single() as? WriteBlock ?: return null
        if (writeBlock.pointerDelta != 0) return null

        val writes = writeBlock.writes.associateBy { it.offset }

        val inductionWrite = writes[0] ?: return null
        val inductionDelta = inductionWrite.expr - Expression.cell(0)
        if (!inductionDelta.isConstant || inductionDelta.constant == 0) return null

        val inv = modInverse(-inductionDelta.constant, 1 shl Byte.SIZE_BITS) ?: return null
        val iterations = Expression(terms = listOf(Expression.Term(inv, listOf(0))))

        val deltas = mutableMapOf<Int, Expression>()
        for ((offset, write) in writes) {
            if (offset == 0) continue
            deltas[offset] = write.expr - Expression.cell(offset)
        }

        return LoopAnalysis(writeBlock, writes, deltas, inv, iterations)
    }

    private fun solvable(deltas: Map<Int, Expression>, modifiedOffsets: Set<Int>): Boolean =
        deltas.values.all { d -> d.terms.flatMap { it.offsets }.none { it in modifiedOffsets } }

    /**
     * propagate loop-invariant assignments through deltas until no new invariants are discovered.
     *
     * after peeling one iteration, any cell whose write expression no longer depends on the
     * remaining modified set has a fixed value for the rest of the loop and can be substituted.
     * returns the substituted deltas, or null if no invariant cells exist (splitting can't help).
     */
    private fun propagateInvariants(
        writes: Map<Int, Write>,
        deltas: Map<Int, Expression>,
    ): Map<Int, Expression>? {
        val invariants = mutableMapOf<Int, Expression>()
        var substituted = deltas
        var changed = true

        while (changed) {
            changed = false
            val unresolved = writes.keys.filterTo(mutableSetOf()) { it == 0 || it !in invariants }

            for ((off, w) in writes) {
                if (off == 0 || off in invariants) continue

                val simplifiedExpr = w.expr.substitute(invariants)
                if (simplifiedExpr == Expression.cell(off)) {
                    invariants[off] = simplifiedExpr
                    changed = true
                    continue
                }

                val dependsOnUnresolved = simplifiedExpr.terms
                    .flatMap { it.offsets }
                    .any { it in unresolved }

                if (!dependsOnUnresolved) {
                    invariants[off] = simplifiedExpr
                    changed = true
                }
            }

            substituted = deltas.mapValues { (_, d) -> d.substitute(invariants) }
        }

        return substituted.takeIf { invariants.isNotEmpty() }
    }

    private fun buildSolved(deltas: Map<Int, Expression>, iterations: Expression): WriteBlock {
        val resultWrites = mutableListOf(Write(0, Expression.ZERO))

        for ((offset, d) in deltas.filterValues { it != Expression.ZERO }) {
            resultWrites += Write(offset, Expression.cell(offset) + d * iterations)
        }

        return WriteBlock(
            pointerDelta = 0,
            writes = orderWrites(resultWrites),
            workingOffset = 0,
        )
    }

    internal fun trySolveLoop(body: List<BfBlockOp>): BfBlockOp? {
        val analysis = analyze(body) ?: return null
        val modifiedOffsets = analysis.writes.keys

        if (solvable(analysis.deltas, modifiedOffsets)) {
            return buildSolved(analysis.deltas, analysis.iterations)
        }

        return trySplitSolve(analysis, modifiedOffsets)
    }

    /**
     * split the first iteration and try to solve the remainder with invariant-propagated deltas
     *
     * result is a Loop that executes at most once, since the first block runs one iteration with
     * the original (unknown) cell values, and the second block solves the remaining iterations
     * using invariant values established by the first iteration. the loop exits because the
     * solved block zeroes tape[0].
     */
    private fun trySplitSolve(analysis: LoopAnalysis, modifiedOffsets: Set<Int>): Conditional? {
        val substituted = propagateInvariants(analysis.writes, analysis.deltas) ?: return null

        val effectivelyModified = modifiedOffsets
            .filterTo(mutableSetOf()) { off -> off == 0 || substituted[off] != Expression.ZERO }

        if (!solvable(substituted, effectivelyModified)) return null

        val split = analysis.merged.copy(pointerDelta = 0, workingOffset = 0)
        val remainderIterations = Expression(terms = listOf(Expression.Term(analysis.inv, listOf(0))))
        val solvedRemainder = buildSolved(substituted, remainderIterations)

        return Conditional(body = listOf(split, solvedRemainder))
    }

    private fun mergeBlocks(
        blocks: List<BfBlockOp>,
        cellDefault: (Int) -> Expression = { Expression.cell(it) },
        eliminateDeadWrites: Boolean = false,
    ): List<BfBlockOp> {
        if (blocks.isEmpty()) return emptyList()
        if (blocks.size == 1 && cellDefault(0) == Expression.cell(0) && !eliminateDeadWrites) return blocks

        var ptrOffset = 0
        // offset -> expression, in terms of the original entry state
        val state = mutableMapOf<Int, Expression>()
        val ioOps = mutableListOf<BfIOOp>()

        fun mergeWriteBlock(block: WriteBlock) {
            val base = ptrOffset + block.workingOffset

            // all reads in this batch see pre-batch state
            val snap = state.toMap()
            fun snapResolve(abs: Int): Expression = snap[abs] ?: cellDefault(abs)

            for (write in block.writes) {
                val absTarget = base + write.offset
                val mapping = write.expr.terms
                    .flatMap { it.offsets }
                    .distinct()
                    .associateWith { relOff -> snapResolve(base + relOff) }
                state[absTarget] = write.expr.substitute(mapping)
            }

            ptrOffset += block.pointerDelta
        }

        for (block in blocks) {
            when (block) {
                is WriteBlock -> mergeWriteBlock(block)
                is IOBlock -> {
                    val base = ptrOffset

                    for (op in block.ops) {
                        when (op) {
                            is Output -> {
                                val mapping = op.expr.terms
                                    .flatMap { it.offsets }
                                    .distinct()
                                    .associateWith { relOff -> state[base + relOff] ?: cellDefault(base + relOff) }
                                ioOps += Output(op.expr.substitute(mapping))
                            }
                            is Input -> {
                                val absOffset = base + op.offset
                                ioOps += Input(absOffset)
                                // input overwrites the cell with an unknown value; reset to identity
                                state[absOffset] = Expression.cell(absOffset)
                            }
                        }
                    }

                    ptrOffset += block.pointerDelta
                }
                is Conditional -> {
                    // check if the guard cell (tape[ptrOffset]) is symbolically known
                    val guardExpr = state[ptrOffset] ?: cellDefault(ptrOffset)
                    if (guardExpr.isConstant && guardExpr.constant == 0) {
                        // guard is zero — the conditional body never runs, skip it
                    } else if (guardExpr.isConstant && guardExpr.constant != 0) {
                        // guard is a known nonzero constant — inline the body
                        for (innerBlock in block.body) {
                            when (innerBlock) {
                                is WriteBlock -> mergeWriteBlock(innerBlock)
                                else -> error("cannot merge Conditional with non-WriteBlock body: $innerBlock")
                            }
                        }
                    } else {
                        // guard depends on unknown cells — can't inline, bail out of merging
                        return blocks
                    }
                }
                is Loop -> return blocks
            }
        }

        if (eliminateDeadWrites) {
            return if (ioOps.isNotEmpty()) {
                listOf(IOBlock(0, ioOps))
            } else {
                emptyList()
            }
        }

        val writes = state
            .filter { (offset, expr) -> expr != Expression.cell(offset) }
            .map { (offset, expr) -> Write(offset, expr) }

        val result = mutableListOf<BfBlockOp>()

        if (ioOps.isNotEmpty() && writes.isNotEmpty()) {
            result += IOBlock(0, ioOps)
            result += WriteBlock(ptrOffset, orderWrites(writes), 0)
        } else if (ioOps.isNotEmpty()) {
            result += IOBlock(0, ioOps)
        } else if (!eliminateDeadWrites && (writes.isNotEmpty() || ptrOffset != 0)) {
            result += WriteBlock(ptrOffset, if (writes.isNotEmpty()) orderWrites(writes) else emptyList(), 0)
        }

        return result
    }

    // endregion

    // region write ordering

    /**
     * topologically order writes so that a reads from any cell come before the write to that cell, if possible.
     * this lets backends avoid needing to allocate temporary locals for reads.
     */
    private fun orderWrites(writes: List<Write>): List<Write> {
        if (writes.size <= 1) return writes

        val targetSet = writes.map { it.offset }.toSet()
        val writeByOffset = writes.associateBy { it.offset }

        // edge a -> b: emit a before b
        val outgoing = targetSet.associateWithGuarantee { mutableSetOf<Int>() }
        val indegree = targetSet.associateWithGuarantee { 0 }

        for (write in writes) {
            val reads = write.expr.terms
                .flatMap { it.offsets }
                .filter { it in targetSet && it != write.offset }
                .toSet()
            reads.filter { outgoing[write.offset].add(it) }.forEach {
                indegree[it]++
            }
        }

        val ready = targetSet.filter { indegree[it] == 0 }.sorted().toMutableList()

        val ordered = mutableListOf<Int>()
        while (ready.isNotEmpty()) {
            val target = ready.removeFirst()
            ordered += target
            for (next in outgoing[target]) {
                if (--indegree[next] == 0) {
                    val idx = ready.binarySearch(next).let { if (it < 0) -(it + 1) else it }
                    ready.add(idx, next)
                }
            }
        }

        // add any remaining targets involved in cycles
        if (ordered.size < writes.size) {
            val emitted = ordered.toSet()
            ordered += writes.map { it.offset }.filter { it !in emitted }
        }

        return ordered.map { writeByOffset[it]!! }
    }

    // endregion

    /**
     * compute the modular multiplicative inverse of [a] mod [m],
     * or null if gcd(a, m) != 1 (i.e., not invertible).
     */
    private fun modInverse(a: Int, m: Int): Int? {
        val a0 = ((a % m) + m) % m
        if (a0 == 0) return null

        var (oldR, r) = a0 to m
        var (oldS, s) = 1 to 0

        while (r != 0) {
            val q = oldR / r

            val tmpR = r
            r = oldR - q * r
            oldR = tmpR

            val tmpS = s
            s = oldS - q * s
            oldS = tmpS
        }

        if (oldR != 1) return null
        return ((oldS % m) + m) % m
    }
}
