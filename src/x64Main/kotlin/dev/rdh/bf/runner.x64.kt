package dev.rdh.bf

actual fun systemRunner(options: SystemRunnerOptions): BfRunner {
    return AffineNasmWriter
}