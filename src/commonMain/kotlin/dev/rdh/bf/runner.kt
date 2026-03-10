package dev.rdh.bf

fun interface BfExecutable {
    fun run(input: BfInput, output: BfOutput)
}

interface BfRunner {
    fun compile(program: Iterable<BFOperation>): BfExecutable

    fun run(program: Iterable<BFOperation>, input: BfInput, output: BfOutput) {
        compile(program).run(input, output)
    }
}

/**
 * Options for the system runner, which may be used to enable or disable certain features of the runner.
 * Not all of these options may be supported on all platforms, and may be ignored if the runner does not support them.
 * @param overflowProtection Whether to enable overflow protection for pointer movements and value changes. Defaults to `true`.
 * @param export Whether to write the compiled program to a file. Defaults to `false`.
 * @param debugInfo Whether to include extra debugging information (e.g. local variable names) in the generated program. Defaults to the value of [export].
 * @param executable Whether to include plumbing to make the exported program directly executable. Defaults to the value of [export].
 */
data class SystemRunnerOptions(
    val overflowProtection: Boolean = true,
    val export: Boolean = false,
    val debugInfo: Boolean = export,
    val executable: Boolean = export,
)

expect fun systemRunner(options: SystemRunnerOptions = SystemRunnerOptions()): BfRunner
