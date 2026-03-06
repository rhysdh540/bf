@file:OptIn(ExperimentalWasmInterop::class, ExperimentalJsExport::class)

package dev.rdh.bf

import dev.rdh.bf.opt.bfOptimise
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport

private object HostInput : BfInput {
    override fun readByte(): Int = hostReadByte()
}

private object HostOutput : BfOutput {
    override fun writeByte(value: Int) = hostWriteByte(value)
    override fun flush() = hostFlush()
}

@WasmImport("bf", "read")
private external fun hostReadByte(): Int

@WasmImport("bf", "write")
private external fun hostWriteByte(value: Int)

@WasmImport("bf", "flush")
private external fun hostFlush()

private const val PROGRAM_CACHE_LIMIT = 8

private data class ProgramKey(
    val source: String,
    val optimise: Boolean,
)

private val programHandles = linkedMapOf<ProgramKey, Int>()
private val compiledPrograms = mutableMapOf<Int, BfExecutable>()
private var nextProgramHandle = 1

private fun buildProgram(source: String, optimise: Boolean): List<BFOperation> {
    var program = bfParse(source)
    if (optimise) {
        program = bfOptimise(program)
    }
    return program
}

private fun rememberProgram(key: ProgramKey, executable: BfExecutable): Int {
    if (programHandles.size >= PROGRAM_CACHE_LIMIT) {
        val eldest = programHandles.entries.firstOrNull()
        if (eldest != null) {
            programHandles.remove(eldest.key)
            compiledPrograms.remove(eldest.value)
        }
    }

    val handle = nextProgramHandle++
    programHandles[key] = handle
    compiledPrograms[handle] = executable
    return handle
}

@JsExport
@JsName("compileProgram")
fun compileProgram(source: String, optimise: Boolean): Int {
    val key = ProgramKey(source = source, optimise = optimise)
    val cached = programHandles[key]
    if (cached != null) {
        // touch key so frequently used programs are evicted last
        programHandles.remove(key)
        programHandles[key] = cached
        return cached
    }

    val executable = systemRunner().compile(buildProgram(source, optimise))
    return rememberProgram(key, executable)
}

@JsExport
@JsName("executeProgram")
fun executeProgram(programHandle: Int) {
    val executable = compiledPrograms[programHandle]
        ?: error("Unknown program handle: $programHandle")
    executable.run(HostInput, HostOutput)
}

@JsExport
@JsName("clearProgramCache")
fun clearProgramCache() {
    programHandles.clear()
    compiledPrograms.clear()
    nextProgramHandle = 1
}

@JsExport
@JsName("run")
fun run(source: String, optimise: Boolean) {
    executeProgram(compileProgram(source, optimise))
}
