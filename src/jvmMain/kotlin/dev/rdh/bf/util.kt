@file:JvmName("Brainfuck")
@file:JvmMultifileClass

package dev.rdh.bf

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer

object SysOutOutput : BfOutput {
    override fun writeByte(value: Int) {
        print(value.toChar())
    }

    override fun flush() {
        System.out.flush()
    }
}

object SysInInput : BfInput {
    override fun readByte(): Int = System.`in`.read()
}

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

fun Reader.asBfInput(): BfInput = ReaderInput(this)
fun InputStream.asBfInput(): BfInput = reader().asBfInput()
fun Writer.asBfOutput(): BfOutput = WriterOutput(this)
fun OutputStream.asBfOutput(): BfOutput = writer().asBfOutput()

class BfInputReader(private val input: BfInput) : Reader() {
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

class BfOutputWriter(private val output: BfOutput) : Writer() {
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

fun BfInput.asReader() = when (this) {
    is ReaderInput -> this.reader
    else -> BfInputReader(this)
}
fun BfOutput.asWriter() = when (this) {
    is WriterOutput -> this.writer
    else -> BfOutputWriter(this)
}
