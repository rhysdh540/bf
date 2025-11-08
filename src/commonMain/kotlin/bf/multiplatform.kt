package bf

fun interface OutputConsumer {
    fun write(value: Int)
    fun flush() {}
}

fun interface InputProvider {
    fun read(): Int
}