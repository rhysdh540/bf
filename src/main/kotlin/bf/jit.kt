package bf

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import java.io.Reader
import java.io.Writer
import java.lang.invoke.MethodHandle
import java.util.Objects

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.abs

private fun loadClass(bytes: ByteArray): Class<*> {
    val name = ClassReader(bytes).className.replace('/', '.')
    val cl = object : ClassLoader() {
        override fun findClass(name: String): Class<*> {
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    return cl.loadClass(name)
}

private fun convertHandle(handle: MethodHandle): (Writer, Reader) -> Unit {
    assert(handle.type().returnType() == Void.TYPE)
    assert(handle.type().parameterCount() == 2)
    assert(handle.type().parameterType(0) == Writer::class.java)
    assert(handle.type().parameterType(1) == Reader::class.java)
    return { writer, reader -> handle.invoke(writer, reader) }
}

@OptIn(ExperimentalStdlibApi::class)
fun bfCompile(program: Iterable<BFOperation>): (Writer, Reader) -> Unit {
    val className = "bf/generated/BFProgram$${Objects.hash(System.nanoTime(), program, System.identityHashCode(program)).toHexString()}"
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
    cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)
    val mw = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "run", "(Ljava/io/Writer;Ljava/io/Reader;)V", null, null)
    mw.visitCode()

    val output = 0
    val input = 1

    // initialize tape: byte[bf.SymbolsKt.TAPE_SIZE]
    val tape = 2
    mw.visitFieldInsn(GETSTATIC, "bf/SymbolsKt", "TAPE_SIZE", "I")
    mw.visitIntInsn(NEWARRAY, T_BYTE)
    mw.visitVarInsn(ASTORE, tape)

    // initialize pointer: int
    val pointer = 3
    mw.visitInsn(ICONST_0)
    mw.visitVarInsn(ISTORE, pointer)

    val programList = program.toList()

    fun writeOp(op: BFOperation): Unit = when(op) {
        is PointerMove -> mw.run {
            // pointer = bf.UtilKt.wrappingAdd(pointer, op.value, tape.length)
            visitVarInsn(ILOAD, pointer)
            addIntConstant(op.value)
            visitVarInsn(ALOAD, tape)
            visitInsn(ARRAYLENGTH)
            visitMethodInsn(INVOKESTATIC, "bf/UtilKt", "wrappingAdd", "(III)I", false)
            visitVarInsn(ISTORE, pointer)
        }
        is ValueChange -> mw.run {
            // tape[pointer] += op.value
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            visitInsn(DUP2)
            visitInsn(BALOAD)
            addIntConstant(abs(op.value))
            visitInsn(if (op.value >= 0) IADD else ISUB)
            visitInsn(I2B)
            visitInsn(BASTORE)
        }
        is Print -> mw.run {
            // stdout.write(tape[pointer])
            visitVarInsn(ALOAD, output)
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            visitInsn(BALOAD)
            visitMethodInsn(INVOKEVIRTUAL, "java/io/Writer", "write", "(I)V", false)
        }
        is Input -> mw.run {
            // tape[pointer] = (byte) stdin.read()
            // load tape[pointer]
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)

            // call stdin.read()
            visitVarInsn(ALOAD, input)
            visitMethodInsn(INVOKEVIRTUAL, "java/io/Reader", "read", "()I", false)
            // convert to byte
            visitInsn(I2B)

            // store in tape[pointer]
            visitInsn(BASTORE)
        }
        is Loop -> mw.run {
            // while (tape[pointer] != 0) { ... }
            val loopStart = Label()
            val loopEnd = Label()
            mw.visitLabel(loopStart)
            // if (tape[pointer] == 0) break
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            visitInsn(BALOAD)
            visitJumpInsn(IFEQ, loopEnd)

            // run the loop body
            for (op in op.contents) {
                writeOp(op)
            }

            // jump back to the start of the loop
            mw.visitJumpInsn(GOTO, loopStart)
            mw.visitLabel(loopEnd)
        }
        is SetToZero -> mw.run {
            // tape[pointer] = 0
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            visitInsn(ICONST_0)
            visitInsn(BASTORE)
        }
    }

    for (op in programList) {
        writeOp(op)
    }

    if (System.getProperty("bf.debug.lvs", "false").toBoolean()) {
        mw.visitParameter("stdout", 0)
        mw.visitParameter("stdin", 0)

        mw.visitLocalVariable("tape", "[B", null, Label(), Label(), tape)
        mw.visitLocalVariable("pointer", "I", null, Label(), Label(), pointer)
    }

    mw.visitInsn(RETURN)
    mw.visitMaxs(0, 0)
    mw.visitEnd()
    cw.visitEnd()
    val bytes = cw.toByteArray()

    if (System.getProperty("bf.debug.export", "false").toBoolean()) {
        val path = Path(".bf.out").resolve("${className}.class")
        path.parent.createDirectories()
        path.writeBytes(bytes)
    }

    verifyClass(bytes)

    val cl = loadClass(bytes)
    val lookup = MethodHandles.lookup()
    val method = lookup.findStatic(cl, "run", MethodType.methodType(Void.TYPE, Writer::class.java, Reader::class.java))
    return convertHandle(method)
}

private fun verifyClass(clazz: ByteArray) {
    try {
        CheckClassAdapter.verify(ClassReader(clazz), false, PrintWriter(Writer.nullWriter()))
    } catch (_: AnalyzerException) {
        CheckClassAdapter.verify(ClassReader(clazz), true, PrintWriter(System.err))
    }
}

private fun MethodVisitor.addIntConstant(value: Int) {
    when (value) {
        -1 -> visitInsn(ICONST_M1)
        0 -> visitInsn(ICONST_0)
        1 -> visitInsn(ICONST_1)
        2 -> visitInsn(ICONST_2)
        3 -> visitInsn(ICONST_3)
        4 -> visitInsn(ICONST_4)
        5 -> visitInsn(ICONST_5)
        else -> visitLdcInsn(value)
    }
}