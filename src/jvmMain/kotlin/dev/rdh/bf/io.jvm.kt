package dev.rdh.bf

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.io.Reader
import java.io.Writer

val SysOutOutput = System.out.bfOutput()
val SysErrOutput = System.err.bfOutput()
val SysInInput = System.`in`.bfInput()

class ReaderInput(internal val reader: Reader) : BfInput {
    override fun readByte(): Int = reader.read()
}

class WriterOutput(internal val writer: Writer) : BfOutput {
    override fun writeByte(value: Int) {
        writer.write(value)
    }

    override fun flush() {
        writer.flush()
    }
}

class InputStreamInput(internal val stream: InputStream) : BfInput {
    override fun readByte(): Int = stream.read()
}

class OutputStreamOutput(internal val stream: OutputStream) : BfOutput {
    override fun writeByte(value: Int) {
        stream.write(value)
    }

    override fun flush() {
        stream.flush()
    }
}

class PrintStreamOutput(internal val stream: PrintStream) : BfOutput {
    override fun writeByte(value: Int) {
        stream.print(value.toChar())
    }

    override fun flush() {
        stream.flush()
    }
}

class BfInputReader(internal val input: BfInput) : Reader() {
    override fun read(): Int = input.readByte()

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (len == 0) return 0

        val first = input.readByte()
        if (first < 0) return -1

        cbuf[off] = first.toChar()
        var count = 1
        while (count < len) {
            val next = input.readByte()
            if (next < 0) break
            cbuf[off + count] = next.toChar()
            count++
        }
        return count
    }

    override fun close() = Unit
}

class BfOutputWriter(internal val output: BfOutput) : Writer() {
    override fun write(c: Int) {
        output.writeByte(c)
    }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        for (i in off until off + len) {
            output.writeByte(cbuf[i].code)
        }
    }

    override fun flush() {
        output.flush()
    }

    override fun close() {
        flush()
    }
}

class PrintStreamWriter(internal val stream: PrintStream) : Writer() {
    override fun write(c: Int) {
        stream.write(c)
    }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        stream.write(String(cbuf).toByteArray(), off, len)
    }

    override fun flush() {
        stream.flush()
    }

    override fun close() {
        stream.close()
    }
}

fun Reader.bfInput(): BfInput = when (this) {
    is BfInputReader -> this.input
    else -> ReaderInput(this)
}
fun Writer.bfOutput(): BfOutput = when (this) {
    is BfOutputWriter -> this.output
    else -> WriterOutput(this)
}

fun BfInput.reader() = when (this) {
    is ReaderInput -> this.reader
    is InputStreamInput -> this.stream.reader()
    else -> BfInputReader(this)
}
fun BfOutput.writer() = when (this) {
    is WriterOutput -> this.writer
    is PrintStreamOutput -> PrintStreamWriter(this.stream)
    is OutputStreamOutput -> this.stream.writer()
    else -> BfOutputWriter(this)
}

fun InputStream.bfInput(): BfInput = InputStreamInput(this)
fun OutputStream.bfOutput(): BfOutput = when (this) {
    is PrintStream -> PrintStreamOutput(this)
    else -> OutputStreamOutput(this)
}
