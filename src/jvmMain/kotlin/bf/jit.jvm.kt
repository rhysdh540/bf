package bf

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.CodeSizeEvaluator
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.io.Writer
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.math.absoluteValue
import kotlin.random.Random

@OptIn(ExperimentalStdlibApi::class)
actual fun bfCompile(program: Iterable<BFOperation>, opts: CompileOptions): (OutputConsumer, InputProvider) -> Unit {
    val className = "BFProgram$${Random.nextInt().toHexString()}"
    val cw = ClassWriter(ClassWriter.COMPUTE_MAXS)
    cw.visit(V1_5, ACC_PUBLIC or ACC_SUPER, className, null, "java/lang/Object", null)

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
                parameters("num", "length")
            }
        }
    }

    val mw = cw.method(name = "run", descriptor = desc<Void>(type<OutputConsumer>(), type<InputProvider>()))
    mw.visitCode()

    val output = mw.local<OutputConsumer>(0)
    val input = mw.local<InputProvider>(1)

    val tape = mw.local<ByteArray>(2)
    mw.int(opts.tapeSize)
    mw.newarray<Byte>()
    mw.store(tape)

    // initialize pointer: int
    val pointer = mw.local<Int>(3)
    mw.int(opts.tapeSize / 2)
    mw.store(pointer)

    val copyValue = mw.local<Int>(4)

    fun MethodVisitor.addOffset(offset: Int) {
        if (offset == 0) return
        int(offset.absoluteValue)
        if (offset >= 0) iadd else isub

        if (opts.overflowProtection) {
            if (tapeSizeIsPowerOf2) {
                int(opts.tapeSize - 1)
                iand
            } else {
                int(opts.tapeSize)
                irem

                if (offset < 0) {
                    load(tape)
                    arraylength
                    invokestatic(className, "w", "(II)I")
                }
            }
        }
    }

    // bf code has a lot of repeated loops, so we can reuse the same method
    val loopCache = mutableMapOf<Loop, String>()
    var loopI = 1
    val loopMethodDescriptor = desc<Int>(type<OutputConsumer>(), type<InputProvider>(), type<ByteArray>(), type<Int>())

    // loop bodies go in separate functions, because the jvm can't handle large methods well
    fun makeLoopBody(loop: Loop, writeOp: MethodVisitor.(BFOperation) -> Unit): String {
        return loopCache.getOrPut(loop) {
            val methodName = "loop$loopI"
            loopI++
            cw.method(ACC_PRIVATE or ACC_STATIC, methodName, loopMethodDescriptor) {
                for (op in loop) {
                    writeOp(op)
                }

                load(pointer)
                areturn<Int>()

                if (opts.localVariables) {
                    parameters("out", "in", "tape", "pointer")
                    localName(copyValue, "copyValue")
                }
            }
            methodName
        }
    }

    fun MethodVisitor.writeOp(op: BFOperation): Unit = when(op) {
        is PointerMove -> {
            // pointer += op.value
            load(pointer)
            addOffset(op.value)
            store(pointer)
        }
        is ValueChange -> {
            // tape[pointer + op.offset] += op.value
            load(tape)
            load(pointer)
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
            load(output)

            load(tape)
            load(pointer)
            addOffset(op.offset)
            visitInsn(BALOAD)

            int(0xFF)
            iand

            invokeinterface<OutputConsumer>("write", desc<Void>(type<Int>()))
        }
        is Input -> {
            // tape[pointer + op.offset] = (byte) stdin.read()
            load(tape)
            load(pointer)
            addOffset(op.offset)

            load(input)
            invokeinterface<InputProvider>("read", desc<Int>())
//            i2b

            bastore
        }
        is Loop -> {
            // while (tape[pointer] != 0) { ... }
            val loopStart = Label()
            val loopEnd = Label()
            mark(loopStart)
            // if (tape[pointer] == 0) break
            load(tape)
            load(pointer)
            baload
            ifeq(loopEnd)

            if (op.any { it is Loop }) {
                val methodName = makeLoopBody(op) { writeOp(it) }

                // call the loop body
                load(output)
                load(input)
                load(tape)
                load(pointer)
                invokestatic(className, methodName, loopMethodDescriptor)
                store(pointer)
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
            load(tape)
            load(pointer)
            addOffset(op.offset)
            int(op.value.toInt())
            bastore
        }
        is Copy -> {
            // currentValue = tape[pointer] & 0xFF
            load(tape)
            load(pointer)
            baload
            int(0xFF)
            iand
            store(copyValue)

            // tape[pointer] = 0
            load(tape)
            load(pointer)
            int(0)
            bastore

            for ((offset, multiplier) in op.multipliers) {
                // tape[pointer + offset] += (byte) (currentValue * multiplier)
                load(tape)
                load(pointer)
                addOffset(offset)

                dup2
                baload
//                int(0xFF)
//                iand

                load(copyValue)
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
        mw.parameters("out", "in")
        mw.locals(
            tape to "tape",
            pointer to "pointer",
            copyValue to "copyValue",
        )
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
    val type = MethodType.methodType(Void.TYPE, OutputConsumer::class.java, InputProvider::class.java)
    val method = lookup.findStatic(cl, "run", type)
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

private fun convertHandle(handle: MethodHandle): (OutputConsumer, InputProvider) -> Unit {
    assert(handle.type().returnType() == Void.TYPE)
    assert(handle.type().parameterCount() == 2)
    assert(handle.type().parameterType(0) == OutputConsumer::class.java)
    assert(handle.type().parameterType(1) == InputProvider::class.java)
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