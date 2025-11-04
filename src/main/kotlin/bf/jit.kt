@file:JvmName("Brainfuck")
@file:JvmMultifileClass

package bf

import org.objectweb.asm.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.CodeSizeEvaluator
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.util.CheckClassAdapter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Reader
import java.io.Writer
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * Options for customizing the jit
 * @param tapeSize the size of the tape to use. Powers of two are recommended for performance.
 * @param overflowProtection whether to wrap tape accesses. Slows down the program significantly, but can prevent crashes.
 *
 * @param export whether to export the class to a file in the current directory
 * @param localVariables whether to generate local variable names in the class
 * @param mainMethod whether to generate a main method in the class,
 *                   which when run will run the program with `System.in` and `System.out`
 */
data class CompileOptions(
    val tapeSize: Int = TAPE_SIZE,
    val overflowProtection: Boolean = true,

    val export: Boolean = false,
    val localVariables: Boolean = export,
    val mainMethod: Boolean = export,
)

@JvmName("compile")
@OptIn(ExperimentalStdlibApi::class)
fun bfCompile(program: Iterable<BFOperation>, opts: CompileOptions = CompileOptions()): (Writer, Reader) -> Unit {
    val className = "BFProgram$${Random.nextInt().toHexString()}"
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(V1_5, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

    if (opts.mainMethod) {
        cw.method(ACC_PUBLIC or ACC_STATIC, "main", desc<Void>(type<Array<String>>())) {
            // new OutputStreamWriter(System.out)
            new<OutputStreamWriter>()
            dup
            dup
            getstatic<System>("out", "Ljava/io/OutputStream;")
            invokespecial<OutputStreamWriter>("<init>", desc<Void>(type<OutputStream>()))
            store<OutputStreamWriter>(1)

            // new InputStreamReader(System.in)
            new<InputStreamReader>()
            dup
            getstatic<System>("in", "Ljava/io/InputStream;")
            invokespecial<InputStreamReader>("<init>", desc<Void>(type<InputStream>()))

            invokestatic(className, "run", desc<Void>(type<Writer>(), type<Reader>()))
            load<OutputStreamWriter>(1)
            invokevirtual<OutputStreamWriter>("flush", desc<Void>())
            areturn<Void>()

            if (opts.localVariables) {
                visitParameter("args", 0)
                visitLocalVariable("out", "Ljava/io/Writer;", null, Label(), Label(), 1)
            }
        }
    }

    val tapeSizeIsPowerOf2 = opts.tapeSize and (opts.tapeSize - 1) == 0

    // method to wrap negative indices
    if (opts.overflowProtection && !tapeSizeIsPowerOf2) {
        cw.method(ACC_PRIVATE or ACC_STATIC, "w", "(II)I") {
            // method signature: private static int w(int num, int length)
            // return num < 0 ? num + length : num

            val negative = Label()
            load<Int>(0)
            iflt(negative)
            load<Int>(0)
            areturn<Int>()
            mark(negative)
            load<Int>(0)
            load<Int>(1)
            iadd
            areturn<Int>()

            if (opts.localVariables) {
                visitParameter("num", 0)
                visitParameter("length", 0)
            }
        }
    }

    val mw = cw.visitMethod(ACC_PUBLIC or ACC_STATIC, "run", desc<Void>(type<Writer>(), type<Reader>()), null, null)
    mw.visitCode()

    val output = 0
    val input = 1

    val tape = 2
    mw.int(opts.tapeSize)
    mw.visitIntInsn(NEWARRAY, T_BYTE)
    mw.visitVarInsn(ASTORE, tape)

    // initialize pointer: int
    val pointer = 3
    mw.int(opts.tapeSize / 2)
    mw.visitVarInsn(ISTORE, pointer)

    val copyValue = 4

    fun MethodVisitor.addOffset(offset: Int) {
        if (offset == 0) return
        int(offset.absoluteValue)
        visitInsn(if (offset >= 0) IADD else ISUB)

        if (opts.overflowProtection) {
            if (tapeSizeIsPowerOf2) {
                int(opts.tapeSize - 1)
                visitInsn(IAND)
            } else {
                int(opts.tapeSize)
                visitInsn(IREM)

                if (offset < 0) {
                    visitVarInsn(ALOAD, tape)
                    visitInsn(ARRAYLENGTH)
                    visitMethodInsn(INVOKESTATIC, className, "w", "(II)I", false)
                }
            }
        }
    }

    // bf code has a lot of repeated loops, so we can reuse the same method
    val loopCache = mutableMapOf<Loop, String>()
    var loopI = 1
    val loopMethodDescriptor = desc<Int>(type<Writer>(), type<Reader>(), type<ByteArray>(), type<Int>())

    // loop bodies go in separate functions, because the jvm can't handle large methods well
    fun makeLoopBody(loop: Loop, writeOp: MethodVisitor.(BFOperation) -> Unit): String {
        return loopCache.getOrPut(loop) {
            val methodName = "loop$loopI"
            loopI++
            cw.method(ACC_PRIVATE or ACC_STATIC, methodName, loopMethodDescriptor) {
                for (op in loop) {
                    writeOp(op)
                }

                load<Int>(pointer)
                areturn<Int>()

                if (opts.localVariables) {
                    visitParameter("out", 0)
                    visitParameter("in", 0)
                    visitParameter("tape", 0)
                    visitParameter("pointer", 0)
                    visitLocalVariable("copyValue", "I", null, Label(), Label(), copyValue)
                }
            }
            methodName
        }
    }

    fun MethodVisitor.writeOp(op: BFOperation): Unit = when(op) {
        is PointerMove -> {
            // pointer += op.value
            load<Int>(pointer)
            addOffset(op.value)
            store<Int>(pointer)
        }
        is ValueChange -> {
            // tape[pointer + op.offset] += op.value
            load<ByteArray>(tape)
            load<Int>(pointer)
            addOffset(op.offset)

            dup2
            baload

            int(op.value.absoluteValue)
            if (op.value >= 0) iadd else isub
//            i2b

            bastore
        }
        is Print -> {
            // stdout.write(tape[pointer + op.offset])
            load<Writer>(output)

            load<ByteArray>(tape)
            load<Int>(pointer)
            addOffset(op.offset)
            visitInsn(BALOAD)

            int(0xFF)
            iand

            invokevirtual<Writer>("write", desc<Void>(type<Int>()))
        }
        is Input -> {
            // tape[pointer + op.offset] = (byte) stdin.read()
            load<ByteArray>(tape)
            load<Int>(pointer)
            addOffset(op.offset)

            load<Reader>(input)
            invokevirtual<Reader>("read", desc<Int>())
//            i2b

            bastore
        }
        is Loop -> {
            // while (tape[pointer] != 0) { ... }
            val loopStart = Label()
            val loopEnd = Label()
            mark(loopStart)
            // if (tape[pointer] == 0) break
            load<ByteArray>(tape)
            load<Int>(pointer)
            baload
            ifeq(loopEnd)

            if (op.any { it is Loop }) {
                val methodName = makeLoopBody(op) { writeOp(it) }

                // call the loop body
                load<Writer>(output)
                load<Reader>(input)
                load<ByteArray>(tape)
                load<Int>(pointer)
                invokestatic(className, methodName, loopMethodDescriptor)
                store<Int>(pointer)
            } else {
                for (op in op) {
                    writeOp(op)
                }
            }

            // jump back to the start of the loop
            goto(loopStart)
            mark(loopEnd)
        }
        is SetToConstant -> {
            // tape[pointer + op.offset] = op.value
            load<ByteArray>(tape)
            load<Int>(pointer)
            addOffset(op.offset)
            int(op.value.toInt())
            bastore
        }
        is Copy -> {
            // currentValue = tape[pointer] & 0xFF
            load<ByteArray>(tape)
            load<Int>(pointer)
            baload
            int(0xFF)
            iand
            store<Int>(copyValue)

            // tape[pointer] = 0
            load<ByteArray>(tape)
            load<Int>(pointer)
            int(0)
            bastore

            for ((offset, multiplier) in op.multipliers) {
                // tape[pointer + offset] += (byte) (currentValue * multiplier)
                load<ByteArray>(tape)
                load<Int>(pointer)
                addOffset(offset)

                dup2
                baload
//                int(0xFF)
//                iand

                load<Int>(copyValue)
                if (multiplier.absoluteValue != 1) {
                    int(multiplier.absoluteValue)
                    imul
                }
                if (multiplier >= 0) iadd else isub
//                i2b

                bastore
            }
        }
    }

    for (op in program) {
        mw.writeOp(op)
    }

    if (opts.localVariables) {
        mw.visitParameter("out", 0)
        mw.visitParameter("in", 0)

        mw.visitLocalVariable("tape", "[B", null, Label(), Label(), tape)
        mw.visitLocalVariable("pointer", "I", null, Label(), Label(), pointer)
        mw.visitLocalVariable("copyValue", "I", null, Label(), Label(), copyValue)
    }

    mw.areturn<Void>()
    mw.visitMaxs(0, 0)
    mw.visitEnd()
    cw.visitEnd()
    val bytes = cw.toByteArray()

    if (opts.export) {
        val path = Path(".bf.out").resolve("${className}.class")
        path.parent.createDirectories()
        path.writeBytes(bytes)
    }

    warnCodeSize(bytes)

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

private fun warnCodeSize(clazz: ByteArray) {
    var maxSize = "" to 0
    val cn = ClassNode().also { ClassReader(clazz).accept(it, 0) }
    for (method in cn.methods) {
        val evaluator = CodeSizeEvaluator(null)
        method.accept(evaluator)
        if (evaluator.maxSize > 1024 * 8) {
            System.err.println(
                "Warning: Method ${cn.name}.${method.name} won't get jit without -XX:-DontCompileHugeMethods, " +
                        "because it is too large (${evaluator.maxSize} bytes). "
            )
        }
        if (evaluator.maxSize > maxSize.second) {
            maxSize = method.name to evaluator.maxSize
        }
    }
}

private fun verifyClass(clazz: ByteArray) {
    try {
        CheckClassAdapter.verify(ClassReader(clazz), false, PrintWriter(Writer.nullWriter()))
    } catch (_: AnalyzerException) {
        CheckClassAdapter.verify(ClassReader(clazz), true, PrintWriter(System.err))
    }
}

@Suppress("unused")
private val clinit = run {
    Path(".bf.out").toFile().deleteRecursively()
}