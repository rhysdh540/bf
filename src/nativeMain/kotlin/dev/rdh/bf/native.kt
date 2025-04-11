package dev.rdh.bf

import kotlinx.cinterop.CVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.NativePlacement
import kotlinx.cinterop.alignOf
import kotlinx.cinterop.sizeOf

@OptIn(ExperimentalForeignApi::class)
inline fun <reified T : CVariable> NativePlacement.alloc() = alloc(sizeOf<T>(), alignOf<T>()) as T

class NativeExecutable(private val path: String) : BfExecutable {
    @OptIn(ExperimentalForeignApi::class)
    override fun run(input: BfInput, output: BfOutput) {
        platform.posix.system(path)
    }
}

expect val nativeRunner: BfRunner