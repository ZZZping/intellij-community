// "Import extension function 'ext'" "true"
package p

open class A {
    fun <T : CharSequence> T.ext() {}
}

object AObject : A()

fun usage() {
    "hello".<caret>ext()
}

// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ImportFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.imprt.ImportQuickFix