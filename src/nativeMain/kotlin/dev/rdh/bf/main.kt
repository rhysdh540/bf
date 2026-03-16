package dev.rdh.bf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

@OptIn(ExperimentalForeignApi::class)
object Main : CommandLine() {
    override fun readFile(path: String): String {
        val file = fopen(path, "r") ?: throw IllegalArgumentException("Could not open file: $path")
        val output = StringBuilder()
        val buffer = ByteArray(1024)
        try {
            memScoped {
                while (true) {
                    val bytesRead = fread(buffer.refTo(0), 1u, buffer.size.toULong(), file)
                    if (bytesRead <= 0u) break
                    output.append(buffer.toKString(endIndex = bytesRead.toInt()))
                }
            }
        } finally {
            fclose(file)
        }

        return output.toString()
    }

    override val stdin = platform.posix.stdin?.bfInput() ?: throw IllegalStateException("Standard input is not available")
    override val stdout = platform.posix.stdout?.bfOutput() ?: throw IllegalStateException("Standard output is not available")
    override val stderr = platform.posix.stderr?.bfOutput() ?: throw IllegalStateException("Standard error is not available")
    override val nativeCodeType = "native code"
}

fun main(args: Array<String>) = Main.run(args)