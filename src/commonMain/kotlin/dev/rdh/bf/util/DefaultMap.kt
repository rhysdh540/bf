package dev.rdh.bf.util

class DefaultMap<K, V>(
    val initializer: DefaultMap<K, V>.(K) -> V,
    private val back: MutableMap<K, V>,
) : MutableMap<K, V> by back {
    override fun get(key: K) = back.getOrPut(key) { initializer(key) }
}

fun <K, V> defaultMapOf(initializer: DefaultMap<K, V>.(K) -> V): DefaultMap<K, V> {
    return DefaultMap(initializer, mutableMapOf())
}