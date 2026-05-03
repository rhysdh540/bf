package dev.rdh.bf

object Interpreter : BfRunner {
    override fun compile(program: Iterable<Op>, tapeSize: Int): BfExecutable {
        return BfExecutable { input, output ->
            run(program, tapeSize, input, output)
        }
    }

    override fun run(program: Iterable<Op>, tapeSize: Int, input: BfInput, output: BfOutput) {
        with(input) {
            with(output) {
                with(IntArray(tapeSize)) {
                    with(mutableMapOf<Temp, Long>()) {
                        execute(program.toList(), tapeSize / 2)
                    }
                }
            }
        }
        output.flush()
    }

    context(input: BfInput, output: BfOutput, tape: IntArray, temps: MutableMap<Temp, Long>)
    private fun execute(program: List<Op>, ptr: Int): Int {
        var ptr = ptr

        for (op in program) {
            when (op) {
                is MovePtr -> ptr += op.delta
                is SetTemp -> temps[op.temp] = with(ptr) { eval(op.value) }
                is Store -> tape[ptr + op.offset] = truncateByte(with(ptr) { eval(op.value) })
                is Read -> tape[ptr + op.offset] = truncateByte(input.readByte().toLong())
                is Write -> output.writeByte(truncateByte(with(ptr) { eval(op.value) }))
                is Conditional -> {
                    if (tape[ptr + op.offset] != 0) {
                        ptr = execute(op.body, ptr)
                    }
                }
                is Loop -> {
                    while (tape[ptr + op.offset] != 0) {
                        ptr = execute(op.body, ptr)
                    }
                }
            }
        }

        return ptr
    }

    context(tape: IntArray, temps: Map<Temp, Long>, ptr: Int)
    private fun eval(expr: Expr): Long = when (expr) {
        is Const -> expr.value.toLong()
        is Cell -> tape[ptr + expr.offset].toLong()
        is GetTemp -> temps.getValue(expr.temp)
        is Add -> expr.terms.sumOf { eval(it) }
        is Mul -> expr.factors.fold(1L) { acc, factor -> acc * eval(factor) }
        is Neg -> -eval(expr.value)
        is ExactDiv -> exactDivide(eval(expr.numerator), expr.divisor.toLong())
        is Choose -> chooseConst(eval(expr.value), expr.degree)
    }
}
