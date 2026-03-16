package dev.rdh.bf

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.readText

object Main : CommandLine() {
    @OptIn(ExperimentalPathApi::class)
    override fun readFile(path: String): String {
        return Path(path).readText()
    }

    override val stdin = System.`in`.bfInput()
    override val stdout = System.out.bfOutput()
    override val stderr = System.err.bfOutput()

    override val nativeCodeType = "JVM bytecode"

    @JvmStatic
    fun main(args: Array<String>) = run(args)
}
