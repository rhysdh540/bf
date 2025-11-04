@file:JvmName("Brainfuck")
@file:JvmMultifileClass

package bf

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Type
import org.objectweb.asm.commons.InstructionAdapter
import java.io.FilterWriter

fun Int.wrappingAdd(value: Int, limit: Int): Int {
    val result = (this + value) % limit
    return if (result < 0) result + limit else result
}

/**
 * Flushes the output stream after every write to make output smoother
 */
object SysOutWriter : FilterWriter(nullWriter()) {
    override fun write(c: Int) {
        print(c.toChar())
    }
}

operator fun <T> MutableList<T>.set(range: IntRange, newList: Iterable<T>) {
    this.subList(range.first, range.last)
        .clear()
    this.addAll(range.first, newList.toList())
}

class DefaultMap<K, V>(val initializer: DefaultMap<K, V>.(K) -> V, private val back: MutableMap<K, V>) : MutableMap<K, V> by back {
    override fun get(key: K) = back.getOrPut(key) { initializer(key) }
}

fun <K, V> defaultMap(initializer: DefaultMap<K, V>.(K) -> V): DefaultMap<K, V> {
    return DefaultMap(initializer, mutableMapOf())
}

fun ClassWriter.method(
    access: Int = ACC_PUBLIC or ACC_STATIC,
    name: String,
    descriptor: String,
    signature: String? = null,
    exceptions: Array<String>? = null,
    block: InstructionAdapter.() -> Unit
) {
    val mv = InstructionAdapter(visitMethod(access, name, descriptor, signature, exceptions))
    mv.visitCode()
    mv.block()
    mv.visitMaxs(0, 0)
    mv.visitEnd()
}

inline fun <reified T> type(): Type = Type.getType(T::class.java)