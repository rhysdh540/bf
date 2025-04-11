package bf

/**
 * The size of the tape used in the Brainfuck interpreter.
 */
const val TAPE_SIZE = 30000

/**
 * Represents an operation in a Brainfuck program.
 */
sealed interface BFOperation

/**
 * Represents a command to move the data pointer some number of cells in a direction.
 * @param value The number of cells to move the pointer. Positive values move right, negative values move left.
 */
data class PointerMove(val value: Int) : BFOperation {
    companion object {
        val Right = PointerMove(1)
        val Left = PointerMove(-1)
    }
}

/**
 * Represents a command to increment or decrement the value at the current data pointer.
 * @param value The amount to increment or decrement the value. Positive values increment, negative values decrement.
 */
data class ValueChange(val value: Int) : BFOperation {
    companion object {
        val Plus = ValueChange(1)
        val Minus = ValueChange(-1)
    }
}

/**
 * Represents a command to output the value at the current data pointer.
 */
object Print : BFOperation {
    override fun toString() = "Print"
}

/**
 * Represents a command to input a value and store it at the current data pointer.
 */
object Input : BFOperation {
    override fun toString() = "Input"
}

/**
 * Represents a loop and its contents.
 */
data class Loop(private val contents: Array<BFOperation>) : BFOperation, List<BFOperation> by contents.toList() {
    constructor(contents: Iterable<BFOperation>) : this(contents.toList().toTypedArray())
    override fun toString() = "Loop($contents)"

    override fun equals(other: Any?): Boolean {
        return this === other || (other is Loop && contents.contentEquals(other.contents))
    }

    override fun hashCode() = contents.contentHashCode()
}

/**
 * Represents a command to set the value at the current data pointer to zero.
 * This isn't a standard Brainfuck command, but is used in the optimised version of the interpreter.
 */
object SetToZero : BFOperation {
    override fun toString() = "SetToZero"
}