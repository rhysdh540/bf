@file:OptIn(ExperimentalWasmInterop::class, ExperimentalJsExport::class)

package dev.rdh.bf

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

private val programHandles = linkedMapOf<String, Int>()
private val compiledPrograms = mutableMapOf<Int, BfExecutable>()
private var nextProgramHandle = 1

private fun rememberProgram(key: String, executable: BfExecutable): Int {
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
fun compileProgram(source: String): Int {
    val cached = programHandles[source]
    if (cached != null) {
        // touch key so frequently used programs are evicted last
        programHandles.remove(source)
        programHandles[source] = cached
        return cached
    }

    val executable = Compiler.compile(Parser.parse(source), 1 shl 15)
    return rememberProgram(source, executable)
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
fun run(source: String) {
    executeProgram(compileProgram(source))
}