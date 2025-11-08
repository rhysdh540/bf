package bf

/**
 * Options for customizing the jit
 * @param tapeSize the size of the tape to use. Powers of two are recommended for performance.
 * @param overflowProtection whether to wrap tape accesses. Slows down the program significantly, but can prevent crashes.
 *
 * @param export whether to export the class to a file in the current directory
 * @param localVariables whether to generate local variable names in the output
 */
data class CompileOptions(
    val tapeSize: Int = TAPE_SIZE,
    val overflowProtection: Boolean = true,

    val export: Boolean = false,
    val localVariables: Boolean = export,
)

expect fun bfCompile(program: Iterable<BFOperation>, opts: CompileOptions = CompileOptions()): (OutputConsumer, InputProvider) -> Unit