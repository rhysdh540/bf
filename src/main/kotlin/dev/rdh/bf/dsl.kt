@file:JvmName("Brainfuck")
@file:JvmMultifileClass

package dev.rdh.bf

interface ProgramDsl {
    fun moveRight(value: Int = 1)
    fun moveLeft(value: Int = 1)
    fun increment(value: Int = 1, offset: Int = 0)
    fun decrement(value: Int = 1, offset: Int = 0)
    fun print(offset: Int = 0)
    fun input(offset: Int = 0)
    fun set(constant: UByte, offset: Int = 0)
    fun setToZero(offset: Int = 0) = set(0u, offset)
    fun copy(firstMultiplier: Pair<Int, Int>, vararg otherMultipliers: Pair<Int, Int>)

    fun loop(block: ProgramDsl.() -> Unit)
}

@JvmName("program")
fun bfProgram(block: ProgramDsl.() -> Unit): List<BFOperation> {
    class Impl : ProgramDsl {
        val ops = mutableListOf<BFOperation>()

        // epic hack to make the below expression bodies
        @Suppress("UnusedReceiverParameter")
        private val Any?.unit: Unit get() = Unit

        override fun moveRight(value: Int) = ops.add(PointerMove(value)).unit
        override fun moveLeft(value: Int) = ops.add(PointerMove(-value)).unit
        override fun increment(value: Int, offset: Int) = ops.add(ValueChange(value, offset)).unit
        override fun decrement(value: Int, offset: Int) = ops.add(ValueChange(-value, offset)).unit
        override fun print(offset: Int) = ops.add(Print(offset)).unit
        override fun input(offset: Int) = ops.add(Input(offset)).unit
        override fun set(constant: UByte, offset: Int) = ops.add(SetToConstant(constant, offset)).unit
        override fun copy(firstMultiplier: Pair<Int, Int>, otherMultipliers: Array<out Pair<Int, Int>>) {
            val m = otherMultipliers.toMap() + firstMultiplier
            if (m.containsKey(0)) {
                throw IllegalArgumentException("Cannot copy to the current cell (offset 0)")
            }

            if (m.isEmpty()) {
                throw IllegalArgumentException("At least one target cell must be specified")
            }

            ops.add(Copy(m))
        }

        override fun loop(block: ProgramDsl.() -> Unit) = ops.add(Loop(bfProgram(block))).unit
    }

    return Impl().apply(block).ops
}