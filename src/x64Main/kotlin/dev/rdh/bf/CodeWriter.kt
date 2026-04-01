package dev.rdh.bf

import dev.rdh.bf.asm.Assembler
import dev.rdh.bf.asm.DataDestination
import dev.rdh.bf.asm.DataSource
import dev.rdh.bf.asm.Immediate
import dev.rdh.bf.asm.Memory
import dev.rdh.bf.asm.Register
import dev.rdh.bf.asm.reqSame
import dev.rdh.bf.asm.rex
import dev.rdh.bf.asm.rm
import dev.rdh.bf.asm.widthOr

class CodeWriter : Assembler {
    private val code = mutableListOf<Byte>()
    private val labels = mutableMapOf<String, Int>() // name to index in code
    private val unresolvedJumps = mutableListOf<UnresolvedJump>()

    fun toByteArray(): ByteArray {
        if (unresolvedJumps.isNotEmpty()) {
            throw IllegalStateException("Unresolved jumps: $unresolvedJumps")
        }

        return code.toByteArray()
    }

    private fun emit(vararg bytes: Int?) {
        code.addAll(bytes.mapNotNull { it?.toByte() })
    }

    // w -> 64-bit operand
    // r -> register in ModR/M reg field is extended (R8-R15)
    // x -> SIB index register is extended
    // b -> ModR/M r/m field or SIB base field is extended
    private fun rex(w: Boolean = false, r: Boolean = false, x: Boolean = false, b: Boolean = false): Int? {
        val byte = 0x40 or
                (if (w) 0b1000 else 0) or
                (if (r) 0b0100 else 0) or
                (if (x) 0b0010 else 0) or
                (if (b) 0b0001 else 0)
        return byte.takeIf { it != 0x40 }
    }

    private fun emitDisp(disp: Long, size: Int) {
        for (i in 0 until size) {
            emit((disp shr (8 * i)).toInt() and 0xFF)
        }
    }

    private fun modRM(mod: Int, reg: UInt, rm: UInt): Int {
        return (mod shl 6) or ((reg and 7u).toInt() shl 3) or (rm and 7u).toInt()
    }

    // width in bits
    private fun imm(imm: Immediate, width: UInt): Array<Int> {
        if (imm.value and ((1L shl width.toInt()) - 1) != imm.value) {
            error("Immediate value ${imm.value} does not fit in $width bits")
        }

        return (0 until (width / 8u).toInt()).map { i ->
            ((imm.value shr (8 * i)) and 0xFF).toInt()
        }.toTypedArray()
    }

    override fun mov(dest: DataDestination, src: DataSource) {
        reqSame(dest, src)
        val dw = dest.widthOr(null)
        when {
            dest is Register && src is Register -> {
                emit(
                    rex(
                        w = dest.width == 64u,
                        r = dest.rex,
                        b = src.rex
                    ),
                    0x8B,
                    modRM(3, dest.rm, src.rm)
                )
            }
            dest is Register && src is Immediate -> {
                val opcode = if (dw == 8u) 0xB0 else 0xB8
                emit(
                    rex(w = dest.width == 64u, b = dest.rex),
                    opcode + dest.rm.toInt(),
                    *imm(src, dw)
                )
            }
            // TODO: memory...
            else -> error("unsupported mov operands: ${dest::class}, ${src::class}")
        }
    }

    override fun movzx(dest: Register, src: DataSource) {
        val srcWidth = src.widthOr(null)
        require(dest.width > srcWidth) { "movzx dest $dest must be wider than source $src" }
        require(srcWidth <= 16u) { "movzx source must be byte or word" }
        TODO("Not yet implemented")
    }

    override fun lea(dest: Register, src: Memory) {
        TODO("Not yet implemented")
    }

    override fun add(dest: DataDestination, src: DataSource) {
        TODO("Not yet implemented")
    }

    override fun sub(dest: DataDestination, src: DataSource) {
        TODO("Not yet implemented")
    }

    override fun imul(dest: Register, src: DataSource, imm: Immediate?) {
        TODO("Not yet implemented")
    }

    override fun inc(dest: DataDestination) {
        TODO("Not yet implemented")
    }

    override fun dec(dest: DataDestination) {
        TODO("Not yet implemented")
    }

    override fun syscall() {
        emit(0x0f, 0x05)
    }

    override fun ret() {
        emit(0xc3)
    }

    override fun call(label: String) {
        emit(0xe8, 0xcd)
        if (labels.containsKey(label)) {
            val offset = labels[label]!! - code.size
            emit(offset and 0xFF, (offset shr 8) and 0xFF, (offset shr 16) and 0xFF, (offset shr 24) and 0xFF)
        } else {
            unresolvedJumps.add(UnresolvedJump(label, code.size))
            emit(0, 0, 0, 0) // placeholder for offset
        }
    }

    override fun cmp(op1: DataDestination, op2: DataSource) {
        TODO("Not yet implemented")
    }

    override fun jmp(label: String) {
        TODO("Not yet implemented")
    }

    override fun je(label: String) {
        emit(0xe9, 0xcd)
        if (labels.containsKey(label)) {
            val offset = labels[label]!! - code.size
            emit(offset and 0xFF, (offset shr 8) and 0xFF, (offset shr 16) and 0xFF, (offset shr 24) and 0xFF)
        } else {
            unresolvedJumps.add(UnresolvedJump(label, code.size))
            emit(0, 0, 0, 0)
        }
    }

    override fun jne(label: String) {
        TODO("Not yet implemented")
    }

    override fun mark(label: String) {
        if (labels.containsKey(label)) {
            throw IllegalStateException("Label `$label` already defined")
        }
        labels[label] = code.size
        val itr = unresolvedJumps.iterator()
        while (itr.hasNext()) {
            val jump = itr.next()
            if (jump.label == label) {
                itr.remove()
                val offset = code.size - jump.index
                code[jump.index] = (offset and 0xFF).toByte()
                code[jump.index + 1] = ((offset shr 8) and 0xFF).toByte()
                code[jump.index + 2] = ((offset shr 16) and 0xFF).toByte()
                code[jump.index + 3] = ((offset shr 24) and 0xFF).toByte()
            }
        }
    }

    override fun push(src: DataSource) {
        TODO("Not yet implemented")
    }

    override fun pop(dest: DataDestination) {
        TODO("Not yet implemented")
    }

    private data class UnresolvedJump(val label: String, val index: Int) {
        override fun toString() = "`$label`@$index"
    }
}