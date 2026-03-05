@file:OptIn(ExperimentalWasmInterop::class)

package dev.rdh.bf

import dev.rdh.bf.opt.bfOptimise
import dev.rdh.bf.opt.bfStrip
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

private fun buildProgram(source: String, optimise: Boolean, strip: Boolean): List<BFOperation> {
    var program = bfParse(source)
    if (optimise) {
        program = bfOptimise(program)
    }
    if (strip) {
        program = bfStrip(program)
    }
    return program
}

@OptIn(ExperimentalJsExport::class)
@JsExport
@JsName("run")
fun run(source: String, optimise: Boolean, strip: Boolean) {
    systemRunner().run(buildProgram(source, optimise, strip), HostInput, HostOutput)
}
