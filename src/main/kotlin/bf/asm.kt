package bf

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type

fun ClassWriter.method(
    access: Int = ACC_PUBLIC or ACC_STATIC,
    name: String,
    descriptor: String,
    signature: String? = null,
    exceptions: Array<String>? = null,
    block: MethodVisitor.() -> Unit
) {
    val mv = visitMethod(access, name, descriptor, signature, exceptions)
    mv.visitCode()
    mv.block()
    mv.visitMaxs(0, 0)
    mv.visitEnd()
}

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