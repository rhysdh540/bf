package bf

/**
 * The size of the tape used in the Brainfuck interpreter.
 */
const val TAPE_SIZE = 30000

/**
 * Represents an operation in a Brainfuck program.
 */
sealed interface BFOperation {
    /**
     * Returns a String of brainfuck code that represents this operation.
     */
    fun toProgramString(): String
}

/**
 * Represents a command to move the data pointer some number of cells in a direction.
 * @param value The number of cells to move the pointer. Positive values move right, negative values move left.
 */
data class PointerMove(val value: Int) : BFOperation {
    companion object {
        val Right = PointerMove(1)
        val Left = PointerMove(-1)
    }

    override fun toProgramString(): String {
        if (value == 0) return ""
        return if (value > 0) {
            ">".repeat(value)
        } else {
            "<".repeat(-value)
        }
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

    override fun toProgramString(): String {
        if (value == 0) return ""
        val action = if (value > 0) {
            "+".repeat(value)
        } else {
            "-".repeat(-value)
        }

        return PointerMove(offset).toProgramString() + action + PointerMove(-offset).toProgramString()
    }
}

/**
 * Represents a command to output the value at the current data pointer.
 */
data class Print(val offset: Int = 0) : BFOperation {
    override fun toString() = "Print"

    override fun toProgramString(): String {
        return PointerMove(offset).toProgramString() + "." + PointerMove(-offset).toProgramString()
    }
}

/**
 * Represents a command to input a value and store it at the current data pointer.
 */
data class Input(val offset: Int = 0) : BFOperation {
    override fun toString() = "Input"

    override fun toProgramString(): String {
        return PointerMove(offset).toProgramString() + "," + PointerMove(-offset).toProgramString()
    }
}

/**
 * Represents a loop and its contents.
 */
data class Loop(internal val contents: Array<BFOperation>) : BFOperation, List<BFOperation> by contents.toList() {
    constructor(contents: Iterable<BFOperation>) : this(contents.toList().toTypedArray())
    override fun toString() = "Loop($contents)"

    override fun equals(other: Any?): Boolean {
        return this === other || (other is Loop && contents.contentEquals(other.contents))
    }

    override fun hashCode() = contents.contentHashCode()

    override fun toProgramString() = "[${contents.joinToString("") { it.toProgramString() }}]"
}

/**
 * Represents a command to set the value at the current data pointer to a constant.
 * This isn't a standard command, but is used in [optimised][bf.opt.bfOptimise] versions of programs.
 */
data class SetToConstant(val value: UByte = 0u, val offset: Int = 0) : BFOperation {
    override fun toProgramString(): String {
        return "[-]" + ValueChange(value.toInt()).toProgramString()
    }
}