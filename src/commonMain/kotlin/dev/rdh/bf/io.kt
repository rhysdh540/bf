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

class ByteInput(private val data: IntArray) : BfInput {
    private var pos = 0

    override fun readByte(): Int {
        if (pos >= data.size) return -1
        return data[pos++]
    }
}

class ByteOutput : BfOutput {
    private val buf = mutableListOf<Int>()

    val bytes: List<Int>
        get() = buf

    override fun writeByte(value: Int) {
        buf.add(value)
    }
}

object NullInput : BfInput {
    override fun readByte(): Int = -1
}

object NullOutput : BfOutput {
    override fun writeByte(value: Int) {}
}