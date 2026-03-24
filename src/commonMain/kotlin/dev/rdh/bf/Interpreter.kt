package dev.rdh.bf

@OptIn(ExperimentalUnsignedTypes::class)
object Interpreter {
    private val TAPE_SIZE = UShort.MAX_VALUE.toInt()

    fun run(program: List<BfBlockOp>, input: BfInput, output: BfOutput) {
        run(program, input, output, UByteArray(TAPE_SIZE), TAPE_SIZE / 2)
        output.flush()
    }

    private fun run(program: List<BfBlockOp>, input: BfInput, output: BfOutput, tape: UByteArray, ptr: Int): Int {
        var ptr = ptr
        for (block in program) {
            when (block) {
                is Block -> {
                    ptr = n(ptr + block.workingOffset)
                    for (op in block.ops) {
                        when (op) {
                            is Input -> {
                                tape[n(ptr + op.offset)] = input.readByte().toUByte()
                            }
                            is Output -> {
                                output.writeByte(tape[n(ptr + op.offset)].toInt())
                            }
                            is WriteBatch -> {
                                if (op.hasCycles) {
                                    // cyclic: we need to capture a snapshot of all the cells we read from before we start writing
                                    val snap = UByteArray(op.readOffsets.size)
                                    for (i in snap.indices) {
                                        snap[i] = tape[n(ptr + op.readOffsets[i])]
                                    }
                                    for (write in op.writes) {
                                        tape[n(ptr + write.offset)] = evalExpr(write.expr) { snap[op.snapshotIndex[it]!!] }.toUByte()
                                    }
                                } else {
                                    // acyclic: just read directly from the tape as we go
                                    for (write in op.writes) {
                                        tape[n(ptr + write.offset)] = evalExpr(write.expr) { tape[n(ptr + it)] }.toUByte()
                                    }
                                }
                            }
                        }
                    }
                    ptr = n(ptr + block.pointerDelta - block.workingOffset)
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

    private fun n(idx: Int): Int {
        return idx and TAPE_SIZE
    }
}
