package dev.rdh.bf

actual fun systemRunner(options: SystemRunnerOptions): BfRunner {
    return NasmWriter
}

context(s: StringBuilder)
operator fun CharSequence.unaryPlus() = s.appendLine(this)
