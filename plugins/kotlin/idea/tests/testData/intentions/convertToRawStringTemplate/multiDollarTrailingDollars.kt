// COMPILER_ARGUMENTS: -Xmulti-dollar-interpolation
// IGNORE_K1

fun test(foo: String) {
    $$"foo$bar$$$" +<caret> "baz$" + "_a$" + "{}$" + "`boo`$" + 0 + "."
}