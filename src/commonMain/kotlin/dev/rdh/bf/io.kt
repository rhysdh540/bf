package dev.rdh.bf

interface BfInput {
    fun readByte(): Int
}

interface BfOutput {
    fun writeByte(value: Int)
    fun flush() {}

    fun write(v: Any?) {
        for (c in v.toString()) {
            writeByte(c.code)
        }
    }
}
