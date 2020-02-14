// !API_VERSION: 1.3
// TARGET_BACKEND: JVM
// IGNORE_BACKEND: JVM_IR
// WITH_RUNTIME

class A {
    fun memberFunction() {}
    val memberProperty: String = ""
}

val topLevelProperty: Int = 0

fun check(reference: Any, expected: String, message: String) {
    val actual = reference.javaClass.declaredMethods.map { it.name }.sorted().toString()
    if (expected != actual) {
        throw AssertionError("Fail on $message. Expected: $expected. Actual: $actual")
    }
}

// We generate _unbound_ callable references as optimized (without overriding getName/getOwner/getSignature in each anonymous subclass)
// even with older API version. This is safe because the corresponding constructor (taking "KDeclarationContainer;String;String;")
// was already present in FunctionReferenceImpl/PropertyReferenceNImpl subclasses since Kotlin 1.0.
// But there was no such constructor for _bound_ references, so for them it's a 1.4+ optimization.
fun box(): String {
    check(A::memberFunction, "[invoke, invoke]", "unbound function reference")
    check(A()::memberFunction, "[getName, getOwner, getSignature, invoke, invoke]", "bound function reference")

    check(::topLevelProperty, "[get]", "unbound property reference 0")
    check(A::memberProperty, "[get]", "unbound property reference 1")
    check(A()::memberProperty, "[get, getName, getOwner, getSignature]", "bound property reference 1")

    return "OK"
}
