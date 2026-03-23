package dev.rdh.bf

actual fun systemRunner(options: SystemRunnerOptions): BfRunner {
    return AffineNasmWriter
}

context(s: StringBuilder)
operator fun CharSequence.unaryPlus() = s.appendLine(this)

interface SysCallProvider {
    val write: ULong
    val read: ULong
    val exit: ULong
}

expect val sys: SysCallProvider