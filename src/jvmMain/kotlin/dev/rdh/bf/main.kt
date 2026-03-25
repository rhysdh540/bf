package dev.rdh.bf

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

object Main : CommandLine() {
    @OptIn(ExperimentalPathApi::class)
    override fun readFile(path: String): String {
        return Path(path).readText()
    }

    override val stdin = System.`in`.bfInput()
    override val stdout = System.out.bfOutput()
    override val stderr = System.err.bfOutput()
    override fun exit(code: Int) = exitProcess(code)

    override val nativeCodeType = "JVM bytecode"
    override val systemRunner = Compiler

    @JvmStatic
    fun main(args: Array<String>) = run(args)
}
