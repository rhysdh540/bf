package dev.rdh.bf.util

class DefaultMap<K, V>(
    private val back: MutableMap<K, V>,
    val initializer: DefaultMap<K, V>.(K) -> V,
) : MutableMap<K, V> by back {
    override fun get(key: K) = back.getOrPut(key) { initializer(key) }
}

fun <K, V> defaultMapOf(initializer: DefaultMap<K, V>.(K) -> V): DefaultMap<K, V> {
    return DefaultMap(mutableMapOf(), initializer)
}

inline fun <K, V> Iterable<K>.associateWithGuarantee(selector: (K) -> V): DefaultMap<K, V> {
    return DefaultMap(this.associateWith(selector).toMutableMap()) { key ->
        throw IllegalStateException("Key $key is not present in the map")
    }
}

fun <K, V> Map<K, V>.withDefault(selector: (K) -> V): DefaultMap<K, V> {
    return DefaultMap(this.toMutableMap()) { selector(it) }
}