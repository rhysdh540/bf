package dev.rdh.bf

fun interface BfInput {
    fun readByte(): Int
}

fun interface BfOutput {
    fun writeByte(value: Int)
    fun flush() {}

    fun write(v: Any?) {
        for (c in v.toString()) {
            writeByte(c.code)
        }
    }
}

class StringInput(private val data: String) : BfInput {
    private var pos = 0

    override fun readByte(): Int {
        if (pos >= data.length) return -1
        return data[pos++].code
    }
}

class StringOutput : BfOutput {
    private val builder = StringBuilder()

    override fun writeByte(value: Int) {
        builder.append(value.toChar())
    }

    override fun toString(): String = builder.toString()
}

object NullInput : BfInput {
    override fun readByte(): Int = -1
}

object NullOutput : BfOutput {
    override fun writeByte(value: Int) {}
}