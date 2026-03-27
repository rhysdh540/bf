package dev.rdh.bf

@OptIn(ExperimentalUnsignedTypes::class)
object Interpreter : BfRunner {
    override fun run(program: Iterable<BfBlockOp>, tapeSize: Int, input: BfInput, output: BfOutput) {
        run(program.toList(), input, output, UByteArray(tapeSize), tapeSize / 2)
        output.flush()
    }

    override fun compile(program: Iterable<BfBlockOp>, tapeSize: Int): BfExecutable {
        return BfExecutable { input, output ->
            run(program, tapeSize, input, output)
        }
    }

    private fun run(program: List<BfBlockOp>, input: BfInput, output: BfOutput, tape: UByteArray, ptr: Int): Int {
        var ptr = ptr
        for (block in program) {
            when (block) {
                is WriteBlock -> {
                    ptr += block.workingOffset
                    if (block.hasCycles) {
                        // capture a snapshot of all the cells we read from before we start writing
                        val snap = UByteArray(block.readOffsets.size)
                        for (i in snap.indices) {
                            snap[i] = tape[ptr + block.readOffsets[i]]
                        }
                        for (write in block.writes) {
                            tape[ptr + write.offset] = evalExpr(write.expr) { snap[block.snapshotIndex[it]!!] }.toUByte()
                        }
                    } else {
                        // no conflicts, just read directly from the tape as we go
                        for (write in block.writes) {
                            tape[ptr + write.offset] = evalExpr(write.expr) { tape[ptr + it] }.toUByte()
                        }
                    }
                    ptr += block.pointerDelta - block.workingOffset
                }
                is IOBlock -> {
                    for (op in block.ops) {
                        when (op) {
                            is Input -> tape[ptr + op.offset] = input.readByte().toUByte()
                            is Output -> output.writeByte(evalExpr(op.expr) { tape[ptr + it] })
                        }
                    }
                    ptr += block.pointerDelta
                }
                is Loop -> {
                    while (tape[ptr] != 0.toUByte()) {
                        ptr = run(block.body, input, output, tape, ptr)
                    }
                }
            }
        }

        return ptr
    }

    private inline fun evalExpr(expr: Expression, accessor: (Int) -> UByte): Int {
        var result = expr.constant
        for (term in expr.terms) {
            var product = term.coeff
            for (offset in term.offsets) {
                product *= accessor(offset).toInt()
            }
            result += product
        }
        return result
    }
}
