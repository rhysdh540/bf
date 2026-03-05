@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.rdh.bf

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

class WasmBinaryenJitRunner(private val options: SystemRunnerOptions) : BfRunner {
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val module = bfCompile(program.toList(), options)
        val wasmBinary = module.emitBinary()
        module.dispose()
        val wasmModule = compileWasmModule(wasmBinary)

        return BfExecutable { input, output ->
            runWasmModule(wasmModule, input::readByte, output::writeByte, output::flush)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun compileWasmModule(wasmBinary: JsAny): JsAny = js("new WebAssembly.Module(wasmBinary)")

@Suppress("UNUSED_PARAMETER")
private fun runWasmModule(wasmModule: JsAny, readByte: () -> Int, writeByte: (Int) -> Unit, flush: () -> Unit) {
    js(
        """
        const instance = new WebAssembly.Instance(wasmModule, {
          bf: {
            read_byte: () => readByte(),
            write_byte: (v) => writeByte(v | 0),
            flush: () => flush(),
          }
        });
        instance.exports.run();
        """
    )
}

internal fun bfCompile(program: List<BFOperation>, options: SystemRunnerOptions): BinaryenModule {
    val ns = binaryen
    val module = ns.newModule()

    module.setFeatures(ns.Features.MVP)

    module.addFunctionImport(
        internalName = "read",
        externalModuleName = "bf",
        externalBaseName = "read_byte",
        params = ns.none,
        results = ns.i32,
    )
    module.addFunctionImport(
        internalName = "write",
        externalModuleName = "bf",
        externalBaseName = "write_byte",
        params = ns.i32,
        results = ns.none,
    )
    module.addFunctionImport(
        internalName = "flush",
        externalModuleName = "bf",
        externalBaseName = "flush",
        params = ns.none,
        results = ns.none,
    )

    val helloBytes = "Hello, world!\n".map { it.code and 0xFF }
    val bodyOps = buildList {
        for (byte in helloBytes) {
            add(
                module.call(
                    name = "write",
                    operands = listOf(module.i32.const(byte)),
                    returnType = ns.none,
                )
            )
        }
        add(module.call(name = "flush", operands = emptyList(), returnType = ns.none))
        add(module.ret())
    }

    val body = module.block(
        label = null,
        children = bodyOps,
        resultType = ns.none,
    )

    module.addFunction(
        name = "run",
        params = ns.none,
        results = ns.none,
        vars = emptyList(),
        body = body,
    )
    module.addFunctionExport("run", "run")

    if (module.validate() == 0) {
        val wat = module.emitText()
        module.dispose()
        throw IllegalStateException("Binaryen validation failed for generated module:\n$wat")
    }

    if (!options.debugInfo) {
        module.optimize()
    }

    return module
}
