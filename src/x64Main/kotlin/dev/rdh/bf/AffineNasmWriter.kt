package dev.rdh.bf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toKString
import kotlin.math.abs

object AffineNasmWriter : BfRunner {
    context(s: StringBuilder)
    private operator fun String.unaryPlus() = s.appendLine(this)

    @OptIn(ExperimentalForeignApi::class)
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val program = bfLowerAffine(program.toList())
        val s = buildString {
            fun addConst(dest: String, n: Int) {
                val op = if (n < 0) "sub" else "add"
                +"    $op $dest, ${abs(n)}"
            }

            +"bits 64"
            +"default rel"

            +"section .bss"
            +"    tape: resb $TAPE_SIZE"

            +"section .text"
            +"global _start"

            +"out:"
            +"    lea rsi, [rbx+rsi]"
            +"    mov eax, ${sys.write}"
            +"    mov edi, 1" // stdout
            +"    mov edx, 1" // write 1 byte
            +"    syscall"
            +"    ret"

            +"in:"
            +"    lea rsi, [rbx+rsi]"
            +"    mov eax, ${sys.read}"
            +"    mov edi, 0" // stdin
            +"    mov edx, 1" // read 1 byte
            +"    syscall"
            +"    ret"

            +"_start:"
            +"    lea rbx, [tape]"
            +"    add rbx, ${TAPE_SIZE / 2}"
            +"    mov rbp, rsp"

            var loopCounter = 0

            fun writeSeg(seg: BFAffineSegment) {
                when (seg) {
                    is BFAffineInput -> {
                        +"    mov rsi, ${seg.offset}"
                        +"    call in"
                    }
                    is BFAffineOutput -> {
                        +"    mov rsi, ${seg.offset}"
                        +"    call out"
                    }
                    is BFAffineWriteBatch -> {
                        val localByRef = mutableMapOf<Int, Int>()
                        for ((i, ref) in seg.refs.withIndex()) {
                            +"    dec rsp" // reserve space for one value
                            +"    movzx rax, byte [rbx + ${ref}]" // load into rax
                            +"    mov [rsp], al" // and store on stack
                            localByRef[ref] = -(i + 1) // offset from rbp
                        }

                        for (write in seg.writes) {
                            +"    mov rax, ${write.expr.constant}"
                            for (term in write.expr.terms) {
                                val local = localByRef[term.offset] ?: error("Reference ${term.offset} not found")
                                if (term.coefficient != 1) {
                                    +"    movzx rdx, byte [rbp + $local]"
                                    +"    imul rdx, ${term.coefficient}"
                                    +"    add rax, rdx"
                                } else {
                                    +"    movzx rdx, byte [rbp + $local]"
                                    +"    add rax, rdx"
                                }
                            }
                            +"    mov byte [rbx + ${write.offset}], al"
                        }
                        +"    mov rsp, rbp"
                    }
                }
            }

            fun writeOp(op: BFAffineOp) {
                when (op) {
                    is BFAffineBlock -> {
                        addConst("rbx", op.baseShift)
                        for (seg in op.segments) {
                            writeSeg(seg)
                        }
                        addConst("rbx", op.pointerDelta - op.baseShift)
                    }
                    is BFAffineLoop -> {
                        val c = loopCounter++
                        +"    jmp LC$c"
                        +"L$c:"
                        for (op in op) {
                            writeOp(op)
                        }
                        +"LC$c:"
                        +"    cmp byte [rbx], 0"
                        +"    jnz L$c"
                    }
                }
            }

            for (op in program) {
                writeOp(op)
            }

            +"    mov rax, ${sys.exit}"
            +"    mov rdi, 0"
            +"    syscall"
        }

        val outfile = platform.posix.tmpnam(null)?.toKString() ?: error("Failed to create temp file")
        val ofile = "$outfile.o"
        val srcfile = "$outfile.nasm"
        val fd = platform.posix.open(srcfile, platform.posix.O_RDWR or platform.posix.O_CREAT, 0b110_110_110) // rw-rw-rw-
        if (fd == -1) error("Failed to open temp file")
        platform.posix.write(fd, s.cstr, s.length.toULong())
        platform.posix.close(fd)

        platform.posix.system("nasm -fmacho64 $srcfile -o $ofile").let {
            if (it != 0) error("Failed to assemble: $it")
        }
        platform.posix.system("ld -o $outfile $ofile -lSystem -syslibroot /Library/Developer/CommandLineTools/SDKs/MacOSX.sdk -e _start -arch x86_64 -platform_version macos 15.0.0 26.2").let {
            if (it != 0) error("Failed to link: $it")
        }

//        platform.posix.remove(srcfile)
        println("Generated assembly:\n$srcfile")
        platform.posix.remove(ofile)

        return NativeExecutable(outfile)
    }
}