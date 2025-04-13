package bf

interface ProgramDsl {
    fun moveRight(value: Int = 1)
    fun moveLeft(value: Int = 1)
    fun increment(value: Int = 1)
    fun decrement(value: Int = 1)
    fun print()
    fun input()
    fun set(constant: UByte)
    fun setToZero() = set(0u)

    fun loop(block: ProgramDsl.() -> Unit)
}

fun bfProgram(block: ProgramDsl.() -> Unit): List<BFOperation> {
    class Impl : ProgramDsl {
        val ops = mutableListOf<BFOperation>()

        // epic hack to make the below expression bodies
        private val Any?.unit: Unit get() = Unit

        override fun moveRight(value: Int) = ops.add(PointerMove(value)).unit
        override fun moveLeft(value: Int) = ops.add(PointerMove(-value)).unit
        override fun increment(value: Int) = ops.add(ValueChange(value)).unit
        override fun decrement(value: Int) = ops.add(ValueChange(-value)).unit
        override fun print() = ops.add(Print()).unit
        override fun input() = ops.add(Input()).unit
        override fun set(constant: UByte) = ops.add(SetToConstant(constant)).unit

        override fun loop(block: ProgramDsl.() -> Unit) = ops.add(Loop(bfProgram(block))).unit
    }

    return Impl().apply(block).ops
}