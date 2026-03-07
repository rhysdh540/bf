@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.rdh.bf

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

class WasmBinaryenJitRunner(private val options: SystemRunnerOptions) : BfRunner {
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val affineProgram = bfLowerAffine(program.toList())
        val module = bfCompile(affineProgram, options)
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

internal fun bfCompile(program: List<BFAffineOp>, options: SystemRunnerOptions): BinaryenModule {
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

    val lowering = makeProgram(ns, module, program)

    val body = module.block(
        label = null,
        children = lowering.ops,
        resultType = ns.none,
    )

    module.addFunction(
        name = "run",
        params = ns.none,
        results = ns.none,
        vars = List(lowering.localCount) { ns.i32 },
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

private data class ProgramLowering(
    val ops: List<BinaryenExprRef>,
    val localCount: Int,
)

private fun makeProgram(ns: BinaryenNamespace, m: BinaryenModule, program: List<BFAffineOp>): ProgramLowering {
    val ops = mutableListOf<BinaryenExprRef>()

    val ptr = 0
    val scratchBase = 1

    ops += m.memory.fill(
        m.i32.const(0),
        m.i32.const(0),
        m.i32.const(TAPE_SIZE),
    )

    ops += m.local.set(ptr, m.i32.const(TAPE_SIZE / 2))

    // sadly we cannot deduplicate loops
    var nLoops = 0
    var maxScratchLocals = 0

    fun ptrGet() = m.local.get(ptr, ns.i32)

    fun load(offset: Int): BinaryenExprRef {
        if (offset < 0) throw IllegalArgumentException("Offset must be non-negative, got $offset")
        return m.i32.load8U(
            offset = offset,
            ptr = ptrGet(),
            align = 1,
        )
    }

    fun store(offset: Int, value: BinaryenExprRef): BinaryenExprRef {
        if (offset < 0) throw IllegalArgumentException("Offset must be non-negative, got $offset")
        return m.i32.store8(
            offset = offset,
            ptr = ptrGet(),
            align = 1,
            value = value,
        )
    }

    fun exprToWasm(expr: BFAffineExpr, localByRef: Map<Int, Int>): BinaryenExprRef {
        if (expr.terms.isEmpty()) {
            return m.i32.const(expr.constant)
        }

        var acc: BinaryenExprRef = m.i32.const(expr.constant)
        for (term in expr.terms) {
            val localIdx = localByRef[term.offset] ?: error("Missing local for ref offset ${term.offset}")
            val refValue = m.local.get(localIdx, ns.i32)
            val termValue = when (term.coefficient) {
                1 -> refValue
                else -> m.i32.mul(refValue, m.i32.const(term.coefficient))
            }
            acc = m.i32.add(acc, termValue)
        }
        return acc
    }

    fun lowerBlock(block: BFAffineBlock): List<BinaryenExprRef> {
        val result = mutableListOf<BinaryenExprRef>()

        if (block.baseShift != 0) {
            result += m.local.set(
                ptr,
                m.i32.add(ptrGet(), m.i32.const(block.baseShift)),
            )
        }

        for (segment in block.segments) {
            when (segment) {
                is BFAffinePrint -> {
                    result += m.call(
                        "write",
                        listOf(load(segment.offset)),
                        ns.none,
                    )
                }

                is BFAffineInput -> {
                    result += store(
                        offset = segment.offset,
                        value = m.call("read", emptyList(), ns.i32),
                    )
                }

                is BFAffineWriteBatch -> {
                    val refs = segment.writes
                        .flatMap { write -> write.expr.terms.map { it.offset } }
                        .distinct()
                        .sorted()

                    maxScratchLocals = maxOf(maxScratchLocals, refs.size)

                    val localByRef = mutableMapOf<Int, Int>()
                    refs.forEachIndexed { i, ref ->
                        val localIdx = scratchBase + i
                        localByRef[ref] = localIdx
                        result += m.local.set(localIdx, load(ref))
                    }

                    for (write in segment.writes) {
                        result += store(
                            offset = write.offset,
                            value = exprToWasm(write.expr, localByRef),
                        )
                    }
                }
            }
        }

        val trailingShift = block.pointerDelta - block.baseShift
        if (trailingShift != 0) {
            result += m.local.set(
                ptr,
                m.i32.add(ptrGet(), m.i32.const(trailingShift)),
            )
        }

        return result
    }

    fun lowerProgram(loweredOps: List<BFAffineOp>): List<BinaryenExprRef> {
        val result = mutableListOf<BinaryenExprRef>()

        for (op in loweredOps) {
            when (op) {
                is BFAffineBlock -> {
                    result += lowerBlock(op)
                }

                is BFAffineLoop -> {
                    val id = nLoops++
                    val exitLabel = "loop_exit_$id"
                    val headLabel = "loop_head_$id"

                    result += m.block(
                        label = exitLabel,
                        children = listOf(
                            m.loop(
                                label = headLabel,
                                body = m.block(
                                    label = null,
                                    children = buildList {
                                        add(
                                            m.brIf(
                                                label = exitLabel,
                                                condition = m.i32.eqz(load(0))
                                            )
                                        )
                                        addAll(lowerProgram(op))
                                        add(m.br(headLabel))
                                    },
                                    resultType = ns.none,
                                )
                            )
                        ),
                        resultType = ns.none,
                    )
                }
            }
        }

        return result
    }

    ops += lowerProgram(program)

    ops += m.call(
        "flush",
        emptyList(),
        ns.none,
    )

    ops += m.ret()
    return ProgramLowering(
        ops = ops,
        localCount = scratchBase + maxScratchLocals,
    )
}
