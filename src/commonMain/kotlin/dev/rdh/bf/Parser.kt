package dev.rdh.bf

import dev.rdh.bf.util.associateWithGuarantee
import dev.rdh.bf.util.defaultMapOf

object Parser {
    fun parse(program: CharSequence): List<BfBlockOp> {
        val opsStack = mutableListOf(mutableListOf<BfBlockOp>())
        val blockStarts = mutableListOf(0)
        val loopOpenIndices = mutableListOf<Int>()

        for (i in 0 until program.length) {
            when (program[i]) {
                '[' -> {
                    appendBlockIfNonEmpty(opsStack.last(), parseBlock(program.subSequence(blockStarts.last(), i)))
                    opsStack.add(mutableListOf())
                    blockStarts.add(i + 1)
                    loopOpenIndices.add(i)
                }

                ']' -> {
                    if (loopOpenIndices.isEmpty()) {
                        error("Unmatched ']' at index $i")
                    }

                    appendBlockIfNonEmpty(opsStack.last(), parseBlock(program.subSequence(blockStarts.last(), i)))
                    blockStarts.removeLast()
                    loopOpenIndices.removeLast()
                    val loopBody = opsStack.removeLast()

                    val list = opsStack.last()
                    if ((opsStack.size != 1 || list.isNotEmpty()) && list.lastOrNull() !is Loop) {
                        val solved = trySolveLoop(loopBody)
                        if (solved != null) {
                            appendBlockIfNonEmpty(list, solved)
                        } else {
                            list += Loop(loopBody)
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
        appendBlockIfNonEmpty(out, parseBlock(program.subSequence(blockStarts.single(), program.length)))
        return out
    }

    private fun appendBlockIfNonEmpty(ops: MutableList<BfBlockOp>, block: Block) {
        if (block.pointerDelta == 0 && block.ops.isEmpty()) return

        // try to merge with the previous block
        val prev = ops.lastOrNull()
        if (prev is Block) {
            val merged = mergeBlocks(listOf(prev, block))
            if (merged != null) {
                ops[ops.lastIndex] = merged
                return
            }
        }

        ops += block
    }

    private fun parseBlock(block: CharSequence): Block {
        var offset = 0
        val ops = mutableListOf<BfOperation>()
        val writeDeltas = defaultMapOf<Int, Int> { 0 }

        fun flushWrites() {
            if (writeDeltas.isNotEmpty()) {
                val writes = writeDeltas.filterValues { it != 0 }.map { (targetOffset, delta) ->
                    Write(targetOffset, AffineExpr(constant = delta, listOf(AffineExpr.Term(1, setOf(targetOffset)))))
                }
                if (writes.isNotEmpty()) {
                    ops += WriteBatch(writes)
                }
                writeDeltas.clear()
            }
        }

        for (c in block) {
            when (c) {
                '>' -> offset++
                '<' -> offset--
                '.' -> {
                    flushWrites()
                    ops += Output(offset)
                }
                ',' -> {
                    flushWrites()
                    ops += Input(offset)
                }
                '+' -> writeDeltas[offset]++
                '-' -> writeDeltas[offset]--
                '[', ']' -> error("[] found in no-loop block: `$block`")
            }
        }

        flushWrites()

        return Block(pointerDelta = offset, ops, workingOffset = 0)
    }

    // region loop solving

    /**
     * try to solve a loop body into a single block
     *
     * a loop is solvable when:
     *  - the body can reduce to a single effective block (so no other loops/io)
     *  - the pointer returns to its initial position after each iteration
     *  - the induction variable (cell 0) changes by a constant amount each iteration coprime to 256 (so that it'll eventually hit zero)
     *  - all other per-iteration deltas only reference cells not modified by the loop
     */
    private fun trySolveLoop(body: List<BfBlockOp>): Block? {
        // body must all be blocks
        val blocks = body.filterIsInstance<Block>()
        if (blocks.size != body.size) return null
        val merged = mergeBlocks(blocks) ?: return null
        if (merged.pointerDelta != 0) return null

        val batch = merged.ops.singleOrNull() as? WriteBatch ?: return null
        val writes = batch.writes.associateBy { it.offset }
        val modifiedOffsets = writes.keys

        val inductionWrite = writes[0] ?: return null
        val inductionDelta = inductionWrite.expr - AffineExpr.cell(0)
        if (!inductionDelta.isConstant) return null
        val delta = inductionDelta.constant
        if (delta == 0) return null

        val inv = modInverse(-delta, 1 shl Byte.SIZE_BITS) ?: return null

        // number of iterations: tape[0] * inv(-delta)
        val tripCount = AffineExpr(terms = listOf(AffineExpr.Term(inv, setOf(0))))

        // check all other deltas don't reference any modified cell
        for ((offset, write) in writes) {
            if (offset == 0) continue
            val d = write.expr - AffineExpr.cell(offset)
            val refs = d.terms.flatMap { it.offsets }.toSet()
            if (refs.any { it in modifiedOffsets }) return null
        }

        // it's solvable! so tape[0] becomes 0, and all other writes get added (delta * tripCount)
        val resultWrites = mutableListOf<Write>()
        resultWrites += Write(0, AffineExpr.const(0))

        for ((offset, write) in writes) {
            if (offset == 0) continue
            val d = write.expr - AffineExpr.cell(offset)
            resultWrites += Write(offset, AffineExpr.cell(offset) + d * tripCount)
        }

        return Block(
            pointerDelta = 0,
            ops = listOf(WriteBatch(orderWrites(resultWrites))),
            workingOffset = 0,
        )
    }

    private fun mergeBlocks(blocks: List<Block>): Block? {
        if (blocks.isEmpty()) return null
        if (blocks.size == 1) return blocks[0]

        var ptrOffset = 0
        // offset -> expression, in terms of the original entry state
        val state = mutableMapOf<Int, AffineExpr>()

        for (block in blocks) {
            val base = ptrOffset + block.workingOffset

            for (op in block.ops) {
                when (op) {
                    is WriteBatch -> {
                        // all reads in this batch see pre-batch state
                        val snap = state.toMap()
                        fun snapResolve(abs: Int): AffineExpr = snap[abs] ?: AffineExpr.cell(abs)

                        for (write in op.writes) {
                            val absTarget = base + write.offset
                            val mapping = write.expr.terms
                                .flatMap { it.offsets }
                                .distinct()
                                .associateWith { relOff -> snapResolve(base + relOff) }
                            state[absTarget] = write.expr.substitute(mapping)
                        }
                    }
                    else -> return null // can't merge across io
                }
            }

            ptrOffset += block.pointerDelta
        }

        val writes = state
            .filter { (offset, expr) -> expr != AffineExpr.cell(offset) }
            .map { (offset, expr) -> Write(offset, expr) }

        val ops = if (writes.isNotEmpty())
            listOf(WriteBatch(orderWrites(writes)))
        else emptyList()

        return Block(
            pointerDelta = ptrOffset,
            ops = ops,
            workingOffset = 0,
        )
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
                if (indegree[next]-- == 0) {
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
