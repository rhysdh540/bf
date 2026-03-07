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
        vars = listOf(ns.i32, ns.i32),
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
    val cachedCopySource = 1

    ops += m.memory.fill(
        m.i32.const(0),
        m.i32.const(0),
        m.i32.const(TAPE_SIZE),
    )

    ops += m.local.set(ptr, m.i32.const(TAPE_SIZE / 2))

    // sadly we cannot deduplicate loops
    var nLoops = 0

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

    data class LoweredValueChange(val offset: Int, val value: Int)
    data class LoweredPrint(val offset: Int)
    data class LoweredInput(val offset: Int)
    data class LoweredSetToConstant(val offset: Int, val value: UByte)
    data class LoweredCopy(val sourceOffset: Int, val targetOffset: Int, val multiplier: Int)

    fun lowerLinearBlock(block: List<BFOperation>): List<BinaryenExprRef> {
        if (block.isEmpty()) return emptyList()

        var pointerDelta = 0
        val lowered = mutableListOf<Any>()

        for (op in block) {
            when (op) {
                is PointerMove -> pointerDelta += op.value
                is ValueChange -> lowered += LoweredValueChange(offset = pointerDelta + op.offset, value = op.value)
                is Print -> lowered += LoweredPrint(offset = pointerDelta + op.offset)
                is Input -> lowered += LoweredInput(offset = pointerDelta + op.offset)
                is SetToConstant -> lowered += LoweredSetToConstant(offset = pointerDelta + op.offset, value = op.value)
                is Copy -> lowered += LoweredCopy(
                    sourceOffset = pointerDelta,
                    targetOffset = pointerDelta + op.offset,
                    multiplier = op.multiplier,
                )
                is Loop -> error("Loop should not appear in linear lowering")
            }
        }

        if (lowered.isEmpty()) {
            return if (pointerDelta == 0) {
                emptyList()
            } else {
                listOf(
                    m.local.set(
                        ptr,
                        m.i32.add(ptrGet(), m.i32.const(pointerDelta)),
                    )
                )
            }
        }

        var baseShift = minOf(0, pointerDelta)
        for (op in lowered) {
            when (op) {
                is LoweredValueChange -> baseShift = minOf(baseShift, op.offset)
                is LoweredPrint -> baseShift = minOf(baseShift, op.offset)
                is LoweredInput -> baseShift = minOf(baseShift, op.offset)
                is LoweredSetToConstant -> baseShift = minOf(baseShift, op.offset)
                is LoweredCopy -> baseShift = minOf(baseShift, op.sourceOffset, op.targetOffset)
            }
        }

        fun eff(offset: Int) = offset - baseShift
        val result = mutableListOf<BinaryenExprRef>()

        if (baseShift != 0) {
            result += m.local.set(
                ptr,
                m.i32.add(ptrGet(), m.i32.const(baseShift)),
            )
        }

        var i = 0
        while (i < lowered.size) {
            val op = lowered[i]

            if (op is LoweredCopy) {
                val source = op.sourceOffset
                var end = i + 1
                while (end < lowered.size) {
                    val next = lowered[end]
                    if (next !is LoweredCopy || next.sourceOffset != source || next.targetOffset == source) {
                        break
                    }
                    end++
                }

                if (end - i >= 2) {
                    result += m.local.set(
                        cachedCopySource,
                        load(eff(source)),
                    )

                    for (j in i until end) {
                        val copy = lowered[j] as LoweredCopy
                        result += store(
                            offset = eff(copy.targetOffset),
                            value = m.i32.add(
                                load(eff(copy.targetOffset)),
                                m.i32.mul(
                                    m.local.get(cachedCopySource, ns.i32),
                                    m.i32.const(copy.multiplier),
                                )
                            )
                        )
                    }

                    i = end
                    continue
                }
            }

            when (op) {
                is LoweredValueChange -> {
                    result += store(
                        offset = eff(op.offset),
                        value = m.i32.add(
                            load(eff(op.offset)),
                            m.i32.const(op.value),
                        )
                    )
                }

                is LoweredPrint -> {
                    result += m.call(
                        "write",
                        listOf(load(eff(op.offset))),
                        ns.none,
                    )
                }

                is LoweredInput -> {
                    result += store(
                        offset = eff(op.offset),
                        value = m.call("read", emptyList(), ns.i32),
                    )
                }

                is LoweredSetToConstant -> {
                    result += store(
                        offset = eff(op.offset),
                        value = m.i32.const(op.value.toInt()),
                    )
                }

                is LoweredCopy -> {
                    result += store(
                        offset = eff(op.targetOffset),
                        value = m.i32.add(
                            load(eff(op.targetOffset)),
                            m.i32.mul(
                                load(eff(op.sourceOffset)),
                                m.i32.const(op.multiplier),
                            )
                        )
                    )
                }
            }

            i++
        }

        val trailingShift = pointerDelta - baseShift
        if (trailingShift != 0) {
            result += m.local.set(
                ptr,
                m.i32.add(ptrGet(), m.i32.const(trailingShift)),
            )
        }

        return result
    }

    fun lowerProgram(program: List<BFOperation>): List<BinaryenExprRef> {
        val result = mutableListOf<BinaryenExprRef>()

        var i = 0
        while (i < program.size) {
            if (program[i] is Loop) {
                val loop = program[i] as Loop
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
                                    addAll(lowerProgram(loop.toList()))
                                    add(m.br(headLabel))
                                },
                                resultType = ns.none,
                            )
                        )
                    ),
                    resultType = ns.none,
                )
                i++
                continue
            }

            val blockStart = i
            while (i < program.size && program[i] !is Loop) {
                i++
            }
            result += lowerLinearBlock(program.subList(blockStart, i))
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
    return ops
}
