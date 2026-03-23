package dev.rdh.bf

import dev.rdh.bf.asm.DataDestination
import dev.rdh.bf.asm.GP32
import dev.rdh.bf.asm.GP64
import dev.rdh.bf.asm.GP8
import dev.rdh.bf.asm.Immediate
import dev.rdh.bf.asm.Memory
import dev.rdh.bf.asm.Nasm
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toKString
import kotlin.math.abs

object AffineNasmWriter : BfRunner {
    @OptIn(ExperimentalForeignApi::class)
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val program = bfLowerAffine(program.toList())

        val s = Nasm {
            fun addConst(dest: DataDestination, n: Int) {
                if (n < 0) {
                    sub(dest, Immediate(-n))
                } else if (n > 0) {
                    add(dest, Immediate(n))
                }
            }

            fun addScaled(coefficient: Int) {
                when (val c = abs(coefficient)) {
                    0 -> return
                    2, 4, 8 -> lea(GP64.RDX, Memory.qword(index = GP64.RDX, scale = c))
                    3, 5, 9 -> lea(GP64.RDX, Memory.qword(GP64.RDX, GP64.RDX, scale = c - 1))
                    else -> imul(GP64.RDX, GP64.RDX, Immediate(c))
                }
                if (coefficient < 0) {
                    sub(GP64.RAX, GP64.RDX)
                } else {
                    add(GP64.RAX, GP64.RDX)
                }
            }

            mark("out")
            lea(GP64.RSI, Memory.qword(GP64.RBX, GP64.RSI))
            mov(GP32.EAX, Immediate(sys.write))
            mov(GP32.EDI, Immediate(1)) // stdout
            mov(GP32.EDX, Immediate(1)) // write 1 byte
            syscall()
            ret()

            mark("in")
            lea(GP64.RSI, Memory.qword(GP64.RBX, GP64.RSI))
            mov(GP32.EAX, Immediate(sys.read))
            mov(GP32.EDI, Immediate(0)) // stdin
            mov(GP32.EDX, Immediate(1)) // read 1 byte
            syscall()
            ret()

            mark("_start")
            addRaw("    lea rbx, [tape]")
            add(GP64.RBX, Immediate(TAPE_SIZE / 2))
            mov(GP64.RBP, GP64.RSP)

            var loopCounter = 0

            fun writeSeg(seg: BFAffineSegment) {
                when (seg) {
                    is BFAffineInput -> {
                        mov(GP64.RSI, Immediate(seg.offset))
                        call("in")
                    }
                    is BFAffineOutput -> {
                        mov(GP64.RSI, Immediate(seg.offset))
                        call("out")
                    }
                    is BFAffineWriteBatch -> {
                        val localByRef = mutableMapOf<Int, Int>()
                        for ((i, ref) in seg.refs.withIndex()) {
                            dec(GP64.RSP) // reserve space for one value
                            movzx(GP64.RAX, Memory.byte(GP64.RBX, displacement = ref.toLong())) // load into rax
                            mov(Memory.byte(GP64.RSP), GP8.AL) // and store on stack
                            localByRef[ref] = -(i + 1) // offset from rbp
                        }

                        for (write in seg.writes) {
                            mov(GP64.RAX, Immediate(write.expr.constant))
                            for (term in write.expr.terms) {
                                val local = localByRef[term.offset] ?: error("Reference ${term.offset} not found")
                                movzx(GP64.RDX, Memory.byte(GP64.RBP, displacement = local.toLong()))
                                addScaled(term.coefficient)
                            }
                            mov(Memory.byte(GP64.RBX, displacement = write.offset.toLong()), GP8.AL)
                        }
                        mov(GP64.RSP, GP64.RBP)
                    }
                }
            }

            fun writeOp(op: BFAffineOp) {
                when (op) {
                    is BFAffineBlock -> {
                        addConst(GP64.RBX, op.baseShift)
                        for (seg in op.segments) {
                            writeSeg(seg)
                        }
                        addConst(GP64.RBX, op.pointerDelta - op.baseShift)
                    }
                    is BFAffineLoop -> {
                        val c = loopCounter++
                        jmp("LC$c")
                        mark("L$c")
                        for (op in op) {
                            writeOp(op)
                        }
                        mark("LC$c")
                        cmp(Memory.byte(GP64.RBX), Immediate(0))
                        jne("L$c")
                    }
                }
            }

            for (op in program) {
                writeOp(op)
            }

            mov(GP32.EAX, Immediate(sys.exit))
            mov(GP64.RDI, Immediate(0))
            syscall()

            addRaw("section .bss")
            addRaw("    tape: resb $TAPE_SIZE")
        }

        val (outfile, srcfile) = Nasm.compile(s)
//        platform.posix.remove(srcfile)
        println("Generated assembly:\n$srcfile")
        return NativeExecutable(outfile)
    }
}