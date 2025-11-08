@file:Suppress("NOTHING_TO_INLINE", "unused")

package bf

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type

fun ClassVisitor.method(
    access: Int = ACC_PUBLIC or ACC_STATIC,
    name: String,
    descriptor: String,
    signature: String? = null,
    exceptions: Array<String>? = null,
    block: MethodVisitor.() -> Unit
) {
    val mv = method(access, name, descriptor, signature, exceptions)
    mv.visitCode()
    mv.block()
    mv.visitMaxs(0, 0)
    mv.visitEnd()
}

fun ClassVisitor.method(
    access: Int = ACC_PUBLIC or ACC_STATIC,
    name: String,
    descriptor: String,
    signature: String? = null,
    exceptions: Array<String>? = null
) = visitMethod(access, name, descriptor, signature, exceptions)

fun MethodVisitor.int(value: Int) {
    when (value) {
        -1 -> visitInsn(ICONST_M1)
        0 -> visitInsn(ICONST_0)
        1 -> visitInsn(ICONST_1)
        2 -> visitInsn(ICONST_2)
        3 -> visitInsn(ICONST_3)
        4 -> visitInsn(ICONST_4)
        5 -> visitInsn(ICONST_5)
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> visitIntInsn(BIPUSH, value)
        in Short.MIN_VALUE..Short.MAX_VALUE -> visitIntInsn(SIPUSH, value)
        else -> visitLdcInsn(value)
    }
}

data class LocalVar(val index: Int, val type: Type)
inline fun <reified T> MethodVisitor.local(index: Int): LocalVar {
    return LocalVar(index, type<T>())
}

inline fun <reified T> MethodVisitor.localName(
    index: Int, name: String,
    startLabel: Label = Label(), endLabel: Label = Label()
) {
    visitLocalVariable(name, type<T>().descriptor, null, startLabel, endLabel, index)
}

inline fun MethodVisitor.localName(
    local: LocalVar, name: String,
    signature: String? = null,
    startLabel: Label = Label(), endLabel: Label = Label()
) {
    visitLocalVariable(name, local.type.descriptor, signature, startLabel, endLabel, local.index)
}

inline fun MethodVisitor.locals(
    vararg locals: Pair<LocalVar, String>,
) = locals.forEach { (local, name) ->
    localName(local, name)
}

inline fun MethodVisitor.parameter(
    name: String, access: Int = 0,
) = visitParameter(name, access)

inline fun MethodVisitor.parameters(
    vararg names: String
) = names.forEach(::parameter)

inline fun <reified T> type(): Type {
    return when (T::class) {
        Boolean::class -> Type.BOOLEAN_TYPE
        Char::class -> Type.CHAR_TYPE
        Byte::class -> Type.BYTE_TYPE
        Short::class -> Type.SHORT_TYPE
        Int::class -> Type.INT_TYPE
        Float::class -> Type.FLOAT_TYPE
        Long::class -> Type.LONG_TYPE
        Double::class -> Type.DOUBLE_TYPE
        Void::class -> Type.VOID_TYPE
        else -> Type.getType(T::class.java)
    }
}
inline fun <reified T> desc(vararg args: Type): String =
    Type.getMethodDescriptor(type<T>(), *args)