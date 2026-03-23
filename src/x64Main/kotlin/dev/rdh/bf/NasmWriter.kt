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

object NasmWriter : BfRunner {
    @OptIn(ExperimentalForeignApi::class)
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val s = Nasm {
            fun addConst(dest: DataDestination, n: Int) {
                if (n < 0) {
                    sub(dest, Immediate(-n))
                } else if (n > 0) {
                    add(dest, Immediate(n))
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

            var loopCounter = 0

            fun writeOp(op: BFOperation) {
                when(op) {
                    is Copy -> {
                        movzx(GP32.EAX, Memory.byte(GP64.RBX))
                        imul(GP32.EAX, GP32.EAX, Immediate(op.multiplier))
                        add(Memory.byte(GP64.RBX, null, 1, op.offset.toLong()), GP8.AL)
                    }
                    is Input -> {
                        mov(GP64.RSI, Immediate(op.offset))
                        call("in")
                    }
                    is Loop -> {
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
                    is PointerMove -> {
                        addConst(GP64.RBX, op.value)
                    }
                    is Print -> {
                        mov(GP64.RSI, Immediate(op.offset))
                        call("out")
                    }
                    is SetToConstant -> {
                        mov(Memory.byte(GP64.RBX, displacement = op.offset.toLong()), Immediate(op.value))
                    }
                    is ValueChange -> {
                        addConst(Memory.byte(GP64.RBX, displacement = op.offset.toLong()), op.value)
                    }
                }
            }

            for (op in program) {
                writeOp(op)
            }

            mov(GP64.RAX, Immediate(sys.exit))
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