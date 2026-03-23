package dev.rdh.bf.asm

interface Assembler {
    fun mov(dest: DataDestination, src: DataSource)
    fun movzx(dest: Register, src: Memory)
    fun lea(dest: Register, src: Memory)
    fun add(dest: DataDestination, src: DataSource)
    fun sub(dest: DataDestination, src: DataSource)
    fun imul(dest: Register, src: DataSource, imm: Immediate? = null)
    fun inc(dest: DataDestination)
    fun dec(dest: DataDestination)
    fun syscall()
    fun ret()
    fun call(label: String)
    fun cmp(op1: DataDestination, op2: DataSource)
    fun jmp(label: String)
    fun jne(label: String)
    fun mark(label: String)
}
