package foo.bar

@JvmName("renamedFooExtensionTest")
private fun Int.foo() = 1

@JvmName("renamedBarFunctionTest")
fun bar() = 2

@JvmName("renamed")
private fun renamed() = 2

// SEARCH_TEXT: renamed
