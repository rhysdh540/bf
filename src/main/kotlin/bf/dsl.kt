package bf

interface ProgramDsl {
    fun moveRight(value: Int = 1)
    fun moveLeft(value: Int = 1)
    fun increment(value: Int = 1)
    fun decrement(value: Int = 1)
    fun print()
    fun input()
    fun setToZero()

    fun loop(block: ProgramDsl.() -> Unit)
}

fun bfProgram(block: ProgramDsl.() -> Unit): List<BFOperation> {
    class Impl : ProgramDsl {
        val ops = mutableListOf<BFOperation>()

        // epic hack to make the below expression bodies
        val Any?.unit: Unit get() = Unit

        override fun moveRight(value: Int) = ops.add(PointerMove(value)).unit
        override fun moveLeft(value: Int) = ops.add(PointerMove(-value)).unit
        override fun increment(value: Int) = ops.add(ValueChange(value)).unit
        override fun decrement(value: Int) = ops.add(ValueChange(-value)).unit
        override fun print() = ops.add(Print).unit
        override fun input() = ops.add(Input).unit
        override fun setToZero() = ops.add(SetToZero).unit

        override fun loop(block: ProgramDsl.() -> Unit) = ops.add(Loop(bfProgram(block))).unit
    }

    return Impl().apply(block).ops
}