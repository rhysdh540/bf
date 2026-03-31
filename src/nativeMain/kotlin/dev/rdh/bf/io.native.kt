@file:OptIn(ExperimentalForeignApi::class)

package dev.rdh.bf

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE

class NativeOutput(internal val file: CPointer<FILE>) : BfOutput {
    override fun writeByte(value: Int) {
        platform.posix.fputc(value, file)
    }

    override fun flush() {
        platform.posix.fflush(file)
    }
}

class NativeInput(internal val file: CPointer<FILE>) : BfInput {
    override fun readByte(): Int {
        return platform.posix.fgetc(file)
    }
}

fun CPointer<FILE>.bfOutput(): BfOutput = NativeOutput(this)
fun CPointer<FILE>.bfInput(): BfInput = NativeInput(this)