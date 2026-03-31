package dev.rdh.bf

import dev.rdh.bf.asm.DataDestination
import dev.rdh.bf.asm.DataSource
import dev.rdh.bf.asm.GP32
import dev.rdh.bf.asm.GP64
import dev.rdh.bf.asm.GP8
import dev.rdh.bf.asm.Immediate
import dev.rdh.bf.asm.Memory
import dev.rdh.bf.asm.Nasm
import dev.rdh.bf.asm.as8
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.collections.plusAssign
import kotlin.math.abs

object NasmWriter : BfRunner {
    @OptIn(ExperimentalForeignApi::class)
    override fun compile(program: Iterable<BfBlockOp>, tapeSize: Int): BfExecutable {
        val s = Nasm {
            fun addConst(dest: DataDestination, n: Int) {
                if (n < 0) {
                    sub(dest, Immediate(-n))
                } else if (n > 0) {
                    add(dest, Immediate(n))
                }
            }

            mark("out")
            push(GP64.RSI)
            mov(GP64.RSI, GP64.RSP)
            mov(GP32.EAX, Immediate(sys.write))
            mov(GP32.EDI, Immediate(platform.posix.STDOUT_FILENO))
            mov(GP32.EDX, Immediate(1)) // write 1 byte
            syscall()
            pop(GP64.RSI)
            ret()

            mark("in")
            lea(GP64.RSI, Memory.qword(GP64.RBX, GP64.RSI))
            mov(GP32.EAX, Immediate(sys.read))
            mov(GP32.EDI, Immediate(platform.posix.STDIN_FILENO))
            mov(GP32.EDX, Immediate(1)) // read 1 byte
            syscall()
            ret()

            mark("_start")
            addRaw("    lea rbx, [tape]")
            add(GP64.RBX, Immediate(tapeSize / 2))
            mov(GP64.RBP, GP64.RSP)

            var loopCounter = 0

            fun writeExpr(expr: Expression, offsetToData: Map<Int, DataSource>, dest: GP64) {
                val temp = if (dest == GP64.RCX) GP64.RAX else GP64.RCX
                mov(dest, Immediate(expr.constant))

                val liveTerms = expr.terms.filter { it.coeff != 0 }
                for (term in liveTerms) {

                    mov(temp, Immediate(abs(term.coeff)))
                    for (off in term.offsets) {
                        val src = offsetToData[off] ?: Memory.byte(GP64.RBX, displacement = off.toLong())
                        movzx(GP64.RDX, src)
                        imul(temp, GP64.RDX)
                    }

                    if (term.coeff >= 0) {
                        add(dest, temp)
                    } else {
                        sub(dest, temp)
                    }
                }
            }

            fun writeBlock(block: BfBlockOp) {
                when (block) {
                    is WriteBlock -> {
                        addConst(GP64.RBX, block.workingOffset)
                        val refsToCache = mutableSetOf<Int>()
                        val refsUsed = mutableSetOf<Int>()
                        val written = mutableSetOf<Int>()
                        for (write in block.writes) {
                            for (term in write.expr.terms) {
                                if (term.coeff == 0) continue
                                for (off in term.offsets) {
                                    if ((off !in refsToCache && off in written) || !refsUsed.add(off)) {
                                        refsToCache += off
                                    }
                                }
                            }
                            written += write.offset
                        }

                        val offsetToData = mutableMapOf<Int, DataSource>()
                        for ((i, ref) in refsToCache.withIndex()) {
                            dec(GP64.RSP) // reserve space for one value
                            movzx(GP64.RAX, Memory.byte(GP64.RBX, displacement = ref.toLong())) // load into rax
                            mov(Memory.byte(GP64.RSP), GP8.AL) // and store on stack
                            offsetToData[ref] = Memory.byte(base = GP64.RBP, displacement = -(i + 1).toLong())
                        }

                        for (write in block.writes) {
                            writeExpr(write.expr, offsetToData, GP64.RAX)
                            mov(Memory.byte(GP64.RBX, displacement = write.offset.toLong()), GP8.AL)
                        }

                        if (refsToCache.isNotEmpty()) {
                            mov(GP64.RSP, GP64.RBP)
                        }
                        addConst(GP64.RBX, block.pointerDelta - block.workingOffset)
                    }
                    is Loop -> {
                        val c = loopCounter++
                        jmp("LC$c")
                        mark("L$c")
                        for (op in block.body) {
                            writeBlock(op)
                        }
                        mark("LC$c")
                        cmp(Memory.byte(GP64.RBX), Immediate(0))
                        jne("L$c")
                    }

                    is Conditional -> {
                        val c = loopCounter++
                        cmp(Memory.byte(GP64.RBX), Immediate(0))
                        je("LC$c")
                        for (op in block.body) {
                            writeBlock(op)
                        }
                        mark("LC$c")
                    }
                    is IOBlock -> {
                        for (op in block.ops) {
                            when (op) {
                                is Input -> {
                                    mov(GP64.RSI, Immediate(op.offset))
                                    call("in")
                                }
                                is Output -> {
                                    writeExpr(op.expr, emptyMap(), GP64.RSI)
                                    call("out")
                                }
                            }
                        }
                        addConst(GP64.RBX, block.pointerDelta)
                    }
                }
            }

            for (op in program) {
                writeBlock(op)
            }

            mov(GP32.EAX, Immediate(sys.exit))
            mov(GP64.RDI, Immediate(0))
            syscall()

            addRaw("section .bss")
            addRaw("    tape: resb $tapeSize")
        }

        val (outfile, srcfile) = Nasm.compile(s)
//        platform.posix.remove(srcfile)
        println("Generated assembly:\n$srcfile")
        return NativeExecutable(outfile)
    }
}