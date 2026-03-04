package dev.rdh.bf

// TODO: jit

actual fun systemRunner(options: SystemRunnerOptions): BfRunner {
    return InterpreterRunner
}

