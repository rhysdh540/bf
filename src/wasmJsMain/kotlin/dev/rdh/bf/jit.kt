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
        val wasmRunner = createWasmRunner(wasmModule)

        return BfExecutable { input, output ->
            runWasmRunner(wasmRunner, input::readByte, output::writeByte, output::flush)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
// language=js prefix=wasmBinary=0;
private fun compileWasmModule(wasmBinary: JsAny): JsAny = js("new WebAssembly.Module(wasmBinary)")

@Suppress("UNUSED_PARAMETER")
// language=js prefix=wasmModule=0;
private fun createWasmRunner(wasmModule: JsAny): JsAny = js("""
        (() => {
          const io = {
            readByte: () => -1,
            writeByte: (_v) => {},
            flush: () => {}
          };
          const instance = new WebAssembly.Instance(wasmModule, {
            bf: {
              read: () => io.readByte(),
              write: (v) => io.writeByte(v | 0),
              flush: () => io.flush(),
            }
          });

          return (readByte, writeByte, flush) => {
            io.readByte = readByte;
            io.writeByte = writeByte;
            io.flush = flush;
            instance.exports.run();
          };
        })()
    """)

@Suppress("UNUSED_PARAMETER")
private fun runWasmRunner(wasmRunner: JsAny, readByte: () -> Int, writeByte: (Int) -> Unit, flush: () -> Unit): Unit =
    // language=js prefix=wasmRunner=,readByte=,writeByte=,flush=0;
    js("wasmRunner(readByte, writeByte, flush)")

internal fun bfCompile(program: List<BFOperation>, options: SystemRunnerOptions): BinaryenModule {
    val ns = binaryen
    val module = ns.newModule()

    module.setFeatures(ns.Features.MVP or ns.Features.BulkMemory or ns.Features.BulkMemoryOpt)

    module.addFunctionImport(
        internalName = "read",
        externalModuleName = "bf",
        externalBaseName = "read",
        params = ns.none,
        results = ns.i32,
    )
    module.addFunctionImport(
        internalName = "write",
        externalModuleName = "bf",
        externalBaseName = "write",
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

    module.setMemory(
        initial = 1,
        maximum = 1,
        exportName = "memory",
    )

    val body = module.block(
        label = null,
        children = makeProgram(ns, module, program),
        resultType = ns.none,
    )

    module.addFunction(
        name = "run",
        params = ns.none,
        results = ns.none,
        vars = listOf(ns.i32),
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

private fun makeProgram(ns: BinaryenNamespace, m: BinaryenModule, program: List<BFOperation>): List<BinaryenExprRef> {
    val ops = mutableListOf<BinaryenExprRef>()

    val ptr = 0

    ops += m.memory.fill(
        m.i32.const(0),
        m.i32.const(0),
        m.i32.const(TAPE_SIZE),
    )

    ops += m.local.set(ptr, m.i32.const(TAPE_SIZE / 2))

    // sadly we cannot deduplicate loops
    var nLoops = 0

    // offset parameters are unsigned in wasm, so we need to implement negative offsets like this
    fun getptr(offset: Int = 0, includePositiveOffset: Boolean = false): BinaryenExprRef {
        val get = m.local.get(ptr, ns.i32)
        return if (offset >= 0) {
            if (includePositiveOffset) m.i32.add(get, m.i32.const(offset)) else get
        } else {
            m.i32.sub(get, m.i32.const(-offset))
        }
    }

    fun load(offset: Int = 0, includePositiveOffset: Boolean = false): BinaryenExprRef {
        return m.i32.load8U(
            offset = if (offset > 0 && !includePositiveOffset) offset else 0,
            ptr = getptr(offset, includePositiveOffset),
            align = 1,
        )
    }

    fun set(offset: Int = 0, value: BinaryenExprRef, includePositiveOffset: Boolean = false): BinaryenExprRef {
        return m.i32.store8(
            offset = if (offset > 0 && !includePositiveOffset) offset else 0,
            ptr = getptr(offset, includePositiveOffset),
            align = 1,
            value = value,
        )
    }

    fun BFOperation.toExpr(): BinaryenExprRef = when (this) {
        is PointerMove -> m.local.set(
            ptr,
            m.i32.add(
                getptr(),
                m.i32.const(this.value),
            )
        )
        is ValueChange -> set(
            offset = this.offset,
            value = m.i32.add(
                load(this.offset),
                m.i32.const(this.value),
            )
        )
        is Print -> m.call(
            "write",
            listOf(load(this.offset)),
            ns.none,
        )
        is Input -> set(
            offset = this.offset,
            value = m.call("read", emptyList(), ns.i32)
        )
        is Loop -> {
            val id = nLoops++
            val exitLabel = "loop_exit_$id"
            val headLabel = "loop_head_$id"

            m.block(
                label = exitLabel,
                children = listOf(
                    m.loop(
                        label = headLabel,
                        body = m.block(
                            label = null,
                            children = buildList {
                                add(m.brIf(
                                    label = exitLabel,
                                    condition = m.i32.eqz(load())
                                ))
                                for (op in this@toExpr) {
                                    add(op.toExpr())
                                }
                                add(m.br(headLabel))
                            },
                            resultType = ns.none,
                        )
                    )
                ),
                resultType = ns.none,
            )
        }
        is SetToConstant -> set(
            offset = this.offset,
            value = m.i32.const(this.value.toInt())
        )
        is Copy -> set(
            offset = this.offset,
            value = m.i32.add(
                load(this.offset),
                m.i32.mul(
                    load(),
                    m.i32.const(this.multiplier),
                )
            )
        )
    }

    for (op in program) {
        ops += op.toExpr()
    }

    ops += m.call(
        "flush",
        emptyList(),
        ns.none,
    )

    ops += m.ret()
    return ops
}
