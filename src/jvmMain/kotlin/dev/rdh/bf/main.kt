package dev.rdh.bf

import java.io.File
import kotlin.system.exitProcess

object Main : CommandLine() {
    override val stdin = System.`in`.bfInput()
    override val stdout = System.out.bfOutput()
    override val stderr = System.err.bfOutput()
    override fun exit(code: Int) = exitProcess(code)

    override val terminal = object : Terminal {
        private var oldSettings: String? = null

        override fun enableRawMode() {
            oldSettings = stty("-g").trim()
            stty("raw", "-echo", "-icanon", "-isig")
        }

        override fun disableRawMode() {
            oldSettings?.let { stty(it) }
            oldSettings = null
        }

        override fun readRawByte(): Int = System.`in`.read()

        private fun stty(vararg args: String): String {
            val pb = ProcessBuilder("stty", *args)
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            val capture = args.size == 1 && args[0] == "-g"
            if (capture) {
                pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            }
            val process = pb.start()
            val result = if (capture) process.inputStream.bufferedReader().readText() else ""
            process.waitFor()
            return result
        }
    }

    override val fs = object : FileSystem {
        override fun readFile(path: String): Result<String> {
            return runCatching { File(path).readText() }
        }

        override fun listFiles(dir: String): List<String> {
            return File(dir).listFiles()?.map { it.name }?.sorted() ?: emptyList()
        }

        override fun exists(path: String): Boolean {
            return File(path).exists()
        }

        override fun isDirectory(path: String): Boolean {
            return File(path).isDirectory
        }
    }

    override val nativeCodeType = "JVM bytecode"
    override val systemRunner = Compiler

    @JvmStatic
    fun main(args: Array<String>) = run(args)
}
