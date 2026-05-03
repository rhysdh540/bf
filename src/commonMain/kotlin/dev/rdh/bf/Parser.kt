package dev.rdh.bf

object Parser {
    fun parse(program: CharSequence): List<Op> {
        val stack = mutableListOf<MutableList<Op>>()
        stack.add(mutableListOf())

        for (c in program) {
            when (c) {
                '>' -> stack.last() += MovePtr(1)
                '<' -> stack.last() += MovePtr(-1)
                '+' -> stack.last() += Store(0, Cell(0) + Const(1))
                '-' -> stack.last() += Store(0, Cell(0) + Const(-1))
                '.' -> stack.last() += Write(Cell(0))
                ',' -> stack.last() += Read(0)
                '[' -> stack.add(mutableListOf())
                ']' -> {
                    val loop = stack.removeLastOrNull() ?: error("Unexpected ]")
                    stack.last() += Loop(0, loop)
                }
            }
        }

        return stack.singleOrNull() ?: error("Unclosed [")
    }
}