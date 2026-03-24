package dev.rdh.bf

import dev.rdh.bf.util.defaultMapOf

// really really bad parser, TODO optimize later
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
                    opsStack.last() += Loop(loopBody)
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
        if (block.pointerDelta != 0 || block.ops.isNotEmpty()) {
            ops += block
        }
    }

    private fun parseBlock(block: CharSequence): Block {
        var offset = 0
        val ops = mutableListOf<BfOperation>()
        val writeDeltas = defaultMapOf<Int, Int> { 0 }

        fun flushWrites() {
            if (writeDeltas.isNotEmpty()) {
                val writes = writeDeltas.filterValues { it != 0 }.map { (targetOffset, delta) ->
                    Write(targetOffset, AffineExpr(constant = delta, listOf(Term(1, listOf(targetOffset)))))
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
}