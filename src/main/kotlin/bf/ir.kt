package bf

/**
 * The size of the tape used in the Brainfuck interpreter.
 */
const val TAPE_SIZE = 1 shl 15 // 32768 cells

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
data class ValueChange(val value: Int, val offset: Int = 0) : BFOperation {
    companion object {
        val Plus = ValueChange(1)
        val Minus = ValueChange(-1)
    }
}

/**
 * Represents a command to output the value at the current data pointer.
 */
data class Print(val offset: Int = 0) : BFOperation

/**
 * Represents a command to input a value and store it at the current data pointer.
 */
data class Input(val offset: Int = 0) : BFOperation

/**
 * Represents a loop and its contents.
 */
data class Loop(internal val contents: Array<BFOperation>) : BFOperation, List<BFOperation> by contents.toList() {
    constructor(contents: Iterable<BFOperation>) : this(contents.toList().toTypedArray())
    override fun toString() = "Loop(${contents.contentToString()})"

    override fun equals(other: Any?): Boolean {
        return this === other || (other is Loop && contents.contentEquals(other.contents))
    }

    override fun hashCode() = contents.contentHashCode()
}

/**
 * Represents a command to set the value at the current data pointer to a constant.
 * This isn't a standard command, but is used in [optimised][bf.opt.bfOptimise] versions of programs.
 */
data class SetToConstant(val value: UByte = 0u, val offset: Int = 0) : BFOperation

/**
 * Represents a command to copy the value at the current data pointer to other cells
 * while setting the current cell to zero.
 * This represents optimized loops like `[->++>>+<<]` which copy the current value
 * to other positions with multipliers.
 * @param multipliers A map where keys are offsets from the current position,
 *                   and values are the multipliers for copying.
 */
data class Copy(val multipliers: Map<Int, Int>) : BFOperation
