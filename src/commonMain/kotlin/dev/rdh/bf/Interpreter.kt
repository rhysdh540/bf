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
                                val idx = n(ptr + op.offset)
                                tape[idx] = input.readByte().toUByte()
                            }
                            is Output -> {
                                val idx = n(ptr + op.offset)
                                output.writeByte(tape[idx].toInt())
                            }
                            is WriteBatch -> {
                                val values = op.readOffsets.distinct().associateWith { tape[n(ptr + it)].toInt() }
                                for (write in op.writes) {
                                    val result = write.expr.constant + write.expr.terms.sumOf { t ->
                                        t.coeff * t.offsets.map { values[it]!! }.fold(1) { a, b -> a * b }
                                    }
                                    tape[n(ptr + write.offset)] = result.toUByte()
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

    private fun n(idx: Int): Int {
        return idx and TAPE_SIZE
    }
}