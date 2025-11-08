package bf

object ConsoleOutput : OutputConsumer {
    override fun write(value: Int) {
        print(value.toChar())
    }
}