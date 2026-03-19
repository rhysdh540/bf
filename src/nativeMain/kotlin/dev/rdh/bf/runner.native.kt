package dev.rdh.bf

import kotlinx.cinterop.ExperimentalForeignApi

class NativeExecutable(private val path: String) : BfExecutable {
    @OptIn(ExperimentalForeignApi::class)
    override fun run(input: BfInput, output: BfOutput) {
        platform.posix.system(path)
    }
}