@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.rdh.bf

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.math.abs

object Compiler : BfRunner {
    override fun compile(program: Iterable<BfBlockOp>, tapeSize: Int): BfExecutable {
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
        val lowering = makeProgram(ns, module, program.toList(), tapeSize)
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

        ns.setOptimizeLevel(3)
        ns.setShrinkLevel(0)
        module.optimize()

        val wasmBinary = module.emitBinary()
        module.dispose()
        val wasmModule = compileWasmModule(wasmBinary)
        val wasmRunner = createWasmRunner(wasmModule)

        return BfExecutable { input, output ->
            runWasmRunner(wasmRunner, input::readByte, output::writeByte, output::flush)
        }
    }

    private data class ProgramLowering(
        val ops: List<BinaryenExprRef>,
        val localCount: Int,
    )

    private fun makeProgram(ns: BinaryenNamespace, m: BinaryenModule, program: List<BfBlockOp>, tapeSize: Int): ProgramLowering {
        val ops = mutableListOf<BinaryenExprRef>()

        val ptr = 0
        val scratchBase = 1

        ops += m.memory.fill(
            m.i32.const(0),
            m.i32.const(0),
            m.i32.const(tapeSize),
        )

        ops += m.local.set(ptr, m.i32.const(tapeSize / 2))

        // sadly we cannot deduplicate loops
        var nLoops = 0
        var maxScratchLocals = 0

        fun ptrGet() = m.local.get(ptr, ns.i32)

        fun load(offset: Int): BinaryenExprRef {
            return if (offset >= 0) {
                m.i32.load8U(offset = offset, ptr = ptrGet(), align = 1)
            } else {
                m.i32.load8U(offset = 0, ptr = m.i32.add(ptrGet(), m.i32.const(offset)), align = 1)
            }
        }

        fun store(offset: Int, value: BinaryenExprRef): BinaryenExprRef {
            return if (offset >= 0) {
                m.i32.store8(offset = offset, ptr = ptrGet(), align = 1, value = value)
            } else {
                m.i32.store8(offset = 0, ptr = m.i32.add(ptrGet(), m.i32.const(offset)), align = 1, value = value)
            }
        }

        fun exprToWasm(expr: AffineExpr, localByRef: Map<Int, Int>): BinaryenExprRef {
            if (expr.terms.isEmpty()) {
                return m.i32.const(expr.constant)
            }

            var result: BinaryenExprRef? = if (expr.constant != 0) m.i32.const(expr.constant) else null

            val liveTerms = expr.terms.filter { it.coeff != 0 }
            for ((i, term) in liveTerms.withIndex()) {
                val offsets = term.offsets.toList()

                // Build the product: |coeff| * cell[off0] * cell[off1] * ...
                var currTerm: BinaryenExprRef
                if (abs(term.coeff) != 1) {
                    currTerm = m.i32.const(abs(term.coeff))
                    for (off in offsets) {
                        val local = localByRef[off]
                        val cell = if (local != null) m.local.get(local, ns.i32) else load(off)
                        currTerm = m.i32.mul(currTerm, cell)
                    }
                } else {
                    // |coeff| == 1: start with first cell, multiply rest
                    var first = true
                    currTerm = offsets.fold(null as BinaryenExprRef?) { acc, off ->
                        val local = localByRef[off]
                        val cell = if (local != null) m.local.get(local, ns.i32) else load(off)
                        if (first) {
                            first = false
                            cell
                        } else {
                            m.i32.mul(acc!!, cell)
                        }
                    } ?: m.i32.const(1)
                }

                // Accumulate into running sum
                if (i != 0 || expr.constant != 0) {
                    result = if (result == null) {
                        currTerm
                    } else if (term.coeff > 0) {
                        m.i32.add(result, currTerm)
                    } else {
                        m.i32.sub(result, currTerm)
                    }
                } else {
                    if (term.coeff < 0) {
                        currTerm = m.i32.sub(m.i32.const(0), currTerm)
                    }
                    result = currTerm
                }
            }

            return result ?: m.i32.const(0)
        }

        fun lowerBlock(block: Block): List<BinaryenExprRef> {
            val result = mutableListOf<BinaryenExprRef>()

            if (block.workingOffset != 0) {
                result += m.local.set(
                    ptr,
                    m.i32.add(ptrGet(), m.i32.const(block.workingOffset)),
                )
            }

            for (segment in block.ops) {
                when (segment) {
                    is Output -> {
                        result += m.call(
                            "write",
                            listOf(load(segment.offset)),
                            ns.none,
                        )
                    }

                    is Input -> {
                        result += store(
                            offset = segment.offset,
                            value = m.call("read", emptyList(), ns.i32),
                        )
                    }

                    is WriteBatch -> {
                        val refsToCache = mutableSetOf<Int>()
                        val refsUsed = mutableSetOf<Int>()
                        val written = mutableSetOf<Int>()
                        for (write in segment.writes) {
                            for (term in write.expr.terms) {
                                if (term.coeff == 0) continue
                                for (off in term.offsets) {
                                    // if the offset will get overwritten later, cache it in a local variable
                                    if ((off !in refsToCache && off in written) || !refsUsed.add(off)) {
                                        refsToCache += off
                                    }
                                }
                            }
                            written += write.offset
                        }

                        val localByRef = mutableMapOf<Int, Int>()
                        for ((i, ref) in refsToCache.withIndex()) {
                            localByRef[ref] = scratchBase + i
                            result += m.local.set(
                                scratchBase + i,
                                load(ref),
                            )
                        }
                        maxScratchLocals = maxOf(maxScratchLocals, refsToCache.size)

                        for (write in segment.writes) {
                            result += store(
                                offset = write.offset,
                                value = exprToWasm(write.expr, localByRef),
                            )
                        }
                    }
                }
            }

            val trailingShift = block.pointerDelta - block.workingOffset
            if (trailingShift != 0) {
                result += m.local.set(
                    ptr,
                    m.i32.add(ptrGet(), m.i32.const(trailingShift)),
                )
            }

            return result
        }

        fun lowerProgram(loweredOps: List<BfBlockOp>): Array<BinaryenExprRef> {
            val result = mutableListOf<BinaryenExprRef>()

            for (op in loweredOps) {
                when (op) {
                    is Block -> {
                        result += lowerBlock(op)
                    }

                    is Loop -> {
                        val id = nLoops++
                        val exitLabel = "e$id"
                        val headLabel = "h$id"

                        result += m.block(
                            label = exitLabel,
                            children = listOf(
                                m.brIf(
                                    label = exitLabel,
                                    condition = m.i32.eqz(load(0))
                                ),
                                m.loop(
                                    label = headLabel,
                                    body = m.block(
                                        label = null,
                                        children = listOf(
                                            *lowerProgram(op.body),
                                            m.brIf(
                                                label = headLabel,
                                                condition = m.i32.ne(load(0), m.i32.const(0))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    }
                }
            }

            return result.toTypedArray()
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