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
import kotlin.math.absoluteValue

/**
 * Options for customizing the jit
 * @param localVariables whether to generate local variable names in the class
 * @param selfContained whether to generate helper methods in the class (if false, will reference members in `bf.UtilKt` and `bf.SymbolsKt`)
 * @param tapeSize the size of the tape, if [selfContained] is true
 * @param mainMethod whether to generate a main method in the class,
 *                   which when run will run the program with `System.in` and `System.out`
 * @param packag the package to put the class in, if empty, will be in `bf.generated`
 * @param export whether to export the class to a file in the current directory
 */
data class CompileOptions(
    val localVariables: Boolean = false,
    val selfContained: Boolean = false,
    val tapeSize: Int = TAPE_SIZE,
    val mainMethod: Boolean = false,
    val packag: String = "bf.generated",

    val export: Boolean = false,
)

@OptIn(ExperimentalStdlibApi::class)
fun bfCompile(program: Iterable<BFOperation>, opts: CompileOptions = CompileOptions()): (Writer, Reader) -> Unit {
    var className = "BFProgram$${Objects.hash(System.nanoTime(), program, System.identityHashCode(program)).toHexString()}"
    if (opts.packag.isNotEmpty()) {
        className = "${opts.packag.replace('.', '/')}/$className"
    }
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
    cw.visit(V1_8, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)
    var mw: MethodVisitor

    if (opts.mainMethod) {
        mw = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
        mw.visitCode()
        // new OutputStreamWriter(System.out)
        mw.visitTypeInsn(NEW, "java/io/OutputStreamWriter")
        mw.visitInsn(DUP)
        mw.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
        mw.visitMethodInsn(INVOKESPECIAL, "java/io/OutputStreamWriter", "<init>", "(Ljava/io/OutputStream;)V", false)
        mw.visitVarInsn(ASTORE, 1)
        mw.visitVarInsn(ALOAD, 1)
        // new InputStreamReader(System.in)
        mw.visitTypeInsn(NEW, "java/io/InputStreamReader")
        mw.visitInsn(DUP)
        mw.visitFieldInsn(GETSTATIC, "java/lang/System", "in", "Ljava/io/InputStream;")
        mw.visitMethodInsn(INVOKESPECIAL, "java/io/InputStreamReader", "<init>", "(Ljava/io/InputStream;)V", false)

        mw.visitMethodInsn(INVOKESTATIC, className, "run", "(Ljava/io/Writer;Ljava/io/Reader;)V", false)
        mw.visitVarInsn(ALOAD, 1)
        mw.visitMethodInsn(INVOKEVIRTUAL, "java/io/OutputStreamWriter", "flush", "()V", false)
        mw.visitInsn(RETURN)

        if (opts.localVariables) {
            mw.visitParameter("args", 0)
            mw.visitLocalVariable("out", "Ljava/io/Writer;", null, Label(), Label(), 1)
        }

        mw.visitMaxs(0, 0)
        mw.visitEnd()
    }

    if (opts.selfContained) {
        mw = cw.visitMethod(ACC_PRIVATE or ACC_STATIC, "wrap", "(III)I", null, null)
        mw.visitCode()
        mw.run {
            visitVarInsn(ILOAD, 0)
            visitVarInsn(ILOAD, 1)
            visitInsn(IADD)

            visitVarInsn(ILOAD, 2)
            visitInsn(IREM)
            visitInsn(DUP)
            visitVarInsn(ISTORE, 0)

            val l = Label()
            visitJumpInsn(IFGE, l)
            visitVarInsn(ILOAD, 0)
            visitVarInsn(ILOAD, 2)
            visitInsn(IADD)
            visitInsn(IRETURN)

            visitLabel(l)
            visitVarInsn(ILOAD, 0)
            visitInsn(IRETURN)

            if (opts.localVariables) {
                visitParameter("base", 0)
                visitParameter("value", 0)
                visitParameter("limit", 0)
            }

            visitMaxs(0, 0)
            visitEnd()
        }
    }

    mw = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "run", "(Ljava/io/Writer;Ljava/io/Reader;)V", null, null)
    mw.visitCode()

    val output = 0
    val input = 1

    val tape = 2
    if (opts.selfContained) {
        mw.pushIntConstant(opts.tapeSize)
    } else {
        mw.visitFieldInsn(GETSTATIC, "bf/SymbolsKt", "TAPE_SIZE", "I")
    }
    mw.visitIntInsn(NEWARRAY, T_BYTE)
    mw.visitVarInsn(ASTORE, tape)

    // initialize pointer: int
    val pointer = 3
    mw.visitInsn(ICONST_0)
    mw.visitVarInsn(ISTORE, pointer)

    val programList = program.toList()

    fun MethodVisitor.writeLoopBody(loop: Loop, writeOp: MethodVisitor.(BFOperation) -> Unit) {
        // method signature: private static int <name>(Writer out, Reader in, byte[] tape, int pointer)
        // so the lvt indices are the same hopefully

        visitCode()

        for (op in loop) {
            writeOp(op)
        }

        visitVarInsn(ILOAD, pointer)
        visitInsn(IRETURN)

        if (opts.localVariables) {
            visitParameter("out", 0)
            visitParameter("in", 0)
            visitParameter("tape", 0)
            visitParameter("pointer", 0)
        }

        visitMaxs(0, 0)
        visitEnd()
    }

    fun MethodVisitor.writeOp(op: BFOperation): Unit = when(op) {
        is PointerMove -> {
            // pointer = bf.UtilKt.wrappingAdd(pointer, op.value, tape.length)
            visitVarInsn(ILOAD, pointer)
            pushIntConstant(op.value)
            visitVarInsn(ALOAD, tape)
            visitInsn(ARRAYLENGTH)
            if (opts.selfContained) {
                visitMethodInsn(INVOKESTATIC, className, "wrap", "(III)I", false)
            } else {
                visitMethodInsn(INVOKESTATIC, "bf/UtilKt", "wrappingAdd", "(III)I", false)
            }
            visitVarInsn(ISTORE, pointer)
        }
        is ValueChange -> {
            // tape[pointer + op.offset] += op.value
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            pushIntConstant(op.offset)
            visitInsn(IADD)

            visitInsn(DUP2)
            visitInsn(BALOAD)

            pushIntConstant(op.value.absoluteValue)
            visitInsn(if (op.value >= 0) IADD else ISUB)
            visitInsn(I2B)

            visitInsn(BASTORE)
        }
        is Print -> {
            // stdout.write(tape[pointer])
            visitVarInsn(ALOAD, output)
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            visitInsn(BALOAD)
            visitIntInsn(SIPUSH, 0xFF)
            visitInsn(IAND)
            visitMethodInsn(INVOKEVIRTUAL, "java/io/Writer", "write", "(I)V", false)
        }
        is Input -> {
            // tape[pointer] = (byte) stdin.read()
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)

            visitVarInsn(ALOAD, input)
            visitMethodInsn(INVOKEVIRTUAL, "java/io/Reader", "read", "()I", false)
            visitInsn(I2B)

            visitInsn(BASTORE)
        }
        is Loop -> {
            // while (tape[pointer] != 0) { ... }
            val loopStart = Label()
            val loopEnd = Label()
            visitLabel(loopStart)
            // if (tape[pointer] == 0) break
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            visitInsn(BALOAD)
            visitJumpInsn(IFEQ, loopEnd)

            // put the loop body in a separate method because the vm can't handle ginormous methods very well
            val methodName = "loopBody$${Objects.hash(System.nanoTime(), op, System.identityHashCode(op)).toHexString()}"
            val desc = "(Ljava/io/Writer;Ljava/io/Reader;[BI)I"
            cw.visitMethod(ACC_PRIVATE or ACC_STATIC, methodName, desc, null, null)
                .writeLoopBody(op, MethodVisitor::writeOp)

            // call the loop body
            visitVarInsn(ALOAD, output)
            visitVarInsn(ALOAD, input)
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            visitMethodInsn(INVOKESTATIC, className, methodName, desc, false)
            visitVarInsn(ISTORE, pointer)

            // jump back to the start of the loop
            visitJumpInsn(GOTO, loopStart)
            visitLabel(loopEnd)
        }
        is SetToConstant -> {
            // tape[pointer] = op.value
            visitVarInsn(ALOAD, tape)
            visitVarInsn(ILOAD, pointer)
            pushIntConstant(op.value.toInt())
            visitInsn(BASTORE)
        }
    }

    for (op in programList) {
        mw.writeOp(op)
    }

    if (opts.localVariables) {
        mw.visitParameter("out", 0)
        mw.visitParameter("in", 0)

        mw.visitLocalVariable("tape", "[B", null, Label(), Label(), tape)
        mw.visitLocalVariable("pointer", "I", null, Label(), Label(), pointer)
    }

    mw.visitInsn(RETURN)
    mw.visitMaxs(0, 0)
    mw.visitEnd()
    cw.visitEnd()
    val bytes = cw.toByteArray()

    if (opts.export) {
        val path = Path(".bf.out").resolve("${className}.class")
        path.parent.createDirectories()
        path.writeBytes(bytes)
    }

    // uncomment to ensure this generates valid classfiles
    // but also 1.5x's the size of the jar
//    verifyClass(bytes)

    val cl = loadClass(bytes)
    val lookup = MethodHandles.lookup()
    val method = lookup.findStatic(cl, "run", MethodType.methodType(Void.TYPE, Writer::class.java, Reader::class.java))
    return convertHandle(method)
}

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

private fun verifyClass(clazz: ByteArray) {
    try {
        CheckClassAdapter.verify(ClassReader(clazz), false, PrintWriter(Writer.nullWriter()))
    } catch (_: AnalyzerException) {
        CheckClassAdapter.verify(ClassReader(clazz), true, PrintWriter(System.err))
    }
}

private fun MethodVisitor.pushIntConstant(value: Int) {
    when (value) {
        -1 -> visitInsn(ICONST_M1)
        0 -> visitInsn(ICONST_0)
        1 -> visitInsn(ICONST_1)
        2 -> visitInsn(ICONST_2)
        3 -> visitInsn(ICONST_3)
        4 -> visitInsn(ICONST_4)
        5 -> visitInsn(ICONST_5)
        else -> {
            if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                visitIntInsn(BIPUSH, value)
            } else if (value in Short.MIN_VALUE..Short.MAX_VALUE) {
                visitIntInsn(SIPUSH, value)
            } else {
                visitLdcInsn(value)
            }
        }
    }
}

@Suppress("unused")
private val clinit = run {
    Path(".bf.out").toFile().deleteRecursively()
}