package bf

import java.io.Reader
import java.io.Writer

object SysOutWriter : OutputConsumer {
    override fun write(value: Int) {
        print(value.toChar())
    }

    override fun flush() {
        System.out.flush()
    }
}

object SysInReader : InputProvider {
    override fun read(): Int {
        return System.`in`.read()
    }
}

class WriterOutputConsumer(val delegate: Writer) : OutputConsumer {
    override fun write(value: Int) {
        delegate.write(value)
    }

    override fun flush() {
        delegate.flush()
    }
}

fun Writer.toOutputConsumer() = WriterOutputConsumer(this)

class ReaderInputProvider(val delegate: Reader) : InputProvider {
    override fun read(): Int {
        return delegate.read()
    }
}

fun Reader.toInputProvider() = ReaderInputProvider(this)