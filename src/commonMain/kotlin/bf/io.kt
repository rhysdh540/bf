package bf

fun interface OutputConsumer {
    fun write(value: Int)
    fun flush() {}
}

fun interface InputProvider {
    fun read(): Int
}

class StringOutput(private val builder: StringBuilder = StringBuilder()) : OutputConsumer {
    override fun write(value: Int) {
        builder.append(value.toChar())
    }

    override fun flush() {}

    override fun toString(): String {
        return builder.toString()
    }
}

class StringInput(private val input: String) : InputProvider {
    private var position: Int = 0

    override fun read(): Int {
        return if (position < input.length) {
            input[position++].code
        } else {
            -1 // EOF
        }
    }
}