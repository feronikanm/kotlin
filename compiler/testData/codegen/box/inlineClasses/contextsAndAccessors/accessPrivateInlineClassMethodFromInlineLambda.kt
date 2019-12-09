// !LANGUAGE: +InlineClasses
// IGNORE_BACKEND_FIR: JVM_IR

inline class R(private val r: Int) {
    fun test() = run { ok() }

    private fun ok() = "OK"
}

fun box() = R(0).test()