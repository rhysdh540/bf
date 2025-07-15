package bf

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

class DefaultMap<K, V>(val initializer: DefaultMap<K, V>.(K) -> V, private val back: MutableMap<K, V>) : MutableMap<K, V> by back {
    override fun get(key: K) = back.getOrPut(key) { initializer(key) }
}

fun <K, V> defaultMap(initializer: DefaultMap<K, V>.(K) -> V): DefaultMap<K, V> {
    return DefaultMap(initializer, mutableMapOf())
}