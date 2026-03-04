package dev.rdh.bf

private class JvmJitRunner(
    private val options: SystemRunnerOptions,
) : BfRunner {
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val compiled = bfCompile(program, options)

        return BfExecutable { i, o ->
            compiled(o.asWriter(), i.asReader())
            o.flush()
        }
    }
}

actual fun systemRunner(options: SystemRunnerOptions): BfRunner {
    return JvmJitRunner(options)
}
