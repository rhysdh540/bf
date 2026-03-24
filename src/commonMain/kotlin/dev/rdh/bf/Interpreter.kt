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
                is Block -> {
                    ptr += block.workingOffset
                    for (op in block.ops) {
                        when (op) {
                            is Input -> {
                                tape[ptr + op.offset] = input.readByte().toUByte()
                            }
                            is Output -> {
                                output.writeByte(tape[ptr + op.offset].toInt())
                            }
                            is WriteBatch -> {
                                if (op.hasCycles) {
                                    // cyclic: we need to capture a snapshot of all the cells we read from before we start writing
                                    val snap = UByteArray(op.readOffsets.size)
                                    for (i in snap.indices) {
                                        snap[i] = tape[ptr + op.readOffsets[i]]
                                    }
                                    for (write in op.writes) {
                                        tape[ptr + write.offset] = evalExpr(write.expr) { snap[op.snapshotIndex[it]!!] }.toUByte()
                                    }
                                } else {
                                    // acyclic: just read directly from the tape as we go
                                    for (write in op.writes) {
                                        tape[ptr + write.offset] = evalExpr(write.expr) { tape[ptr + it] }.toUByte()
                                    }
                                }
                            }
                        }
                    }
                    ptr = ptr + block.pointerDelta - block.workingOffset
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

    private inline fun evalExpr(expr: AffineExpr, accessor: (Int) -> UByte): Int {
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
