package dev.rdh.bf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toKString
import kotlin.math.abs

object NasmWriter : BfRunner {
    context(s: StringBuilder)
    private operator fun String.unaryPlus() = s.appendLine(this)

    @OptIn(ExperimentalForeignApi::class)
    override fun compile(program: Iterable<BFOperation>): BfExecutable {
        val s = buildString {
            fun addConst(dest: String, n: Int) {
                if (n == 0) return
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

            var loopCounter = 0

            fun writeOp(op: BFOperation) {
                when(op) {
                    is Copy -> {
                        +"    movzx eax, byte [rbx]"
                        +"    imul eax, eax, ${op.multiplier}"
                        +"    add byte [rbx + ${op.offset}], al"
                    }
                    is Input -> {
                        +"    mov rsi, ${op.offset}"
                        +"    call in"
                    }
                    is Loop -> {
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
                    is PointerMove -> {
                        addConst("rbx", op.value)
                    }
                    is Print -> {
                        +"    mov rsi, ${op.offset}"
                        +"    call out"
                    }
                    is SetToConstant -> {
                        +"    mov byte [rbx + ${op.offset}], ${op.value}"
                    }
                    is ValueChange -> {
                        addConst("byte [rbx + ${op.offset}]", op.value)
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

interface SysCallProvider {
    val write: ULong
    val read: ULong
    val exit: ULong
}

expect val sys: SysCallProvider