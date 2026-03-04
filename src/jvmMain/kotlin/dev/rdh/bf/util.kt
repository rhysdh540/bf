@file:JvmName("Brainfuck")
@file:JvmMultifileClass

package dev.rdh.bf

import java.io.FilterWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer

object SysOutWriter : FilterWriter(nullWriter()) {
    override fun write(c: Int) {
        print(c.toChar())
    }
}

object SysOutOutput : BfOutput {
    override fun writeByte(value: Int) {
        print(value.toChar())
    }
}

class ReaderInput(private val reader: Reader) : BfInput {
    override fun readByte(): Int = reader.read()
}

class WriterOutput(private val writer: Writer) : BfOutput {
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
