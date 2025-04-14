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