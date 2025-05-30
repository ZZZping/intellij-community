// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring

import com.google.gson.JsonParser
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.classMembers.MemberInfoBase
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KtPsiClassWrapper
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinTestUtils
import org.jetbrains.kotlin.idea.test.util.findElementsByCommentPrefix
import org.jetbrains.kotlin.psi.NotNullableUserDataProperty
import java.io.File

abstract class AbstractMemberPullPushTest : KotlinLightCodeInsightFixtureTestCase() {
    val fixture: JavaCodeInsightTestFixture get() = myFixture

    protected fun doTest(path: String, action: (mainFile: PsiFile) -> Unit) {
        val mainFile = File(path)
        val afterFile = File("$path.after")
        val conflictFile = getConflictFile(path)

        fixture.testDataPath = mainFile.parent

        val mainFileName = mainFile.name
        val mainFileBaseName = FileUtil.getNameWithoutExtension(mainFileName)
        val extraFiles = mainFile.parentFile.listFiles { _, name ->
            name != mainFileName && name.startsWith("$mainFileBaseName.") && (name.endsWith(".kt") || name.endsWith(".java"))
        }
        val extraFilesToPsi = extraFiles.associateBy { fixture.configureByFile(it.name) }
        val file = fixture.configureByFile(mainFileName)

        try {
            markMembersInfo(file)
            extraFilesToPsi.keys.forEach(::markMembersInfo)

            action(file)

            assert(!conflictFile.exists()) { "Conflict file $conflictFile should not exist" }

            PsiDocumentManager.getInstance(project).commitAllDocuments()

            KotlinTestUtils.assertEqualsToFile(afterFile, file.text!!)
            for ((extraPsiFile, extraFile) in extraFilesToPsi) {
                KotlinTestUtils.assertEqualsToFile(File("${extraFile.path}.after"), extraPsiFile.text)
            }
        } catch (e: Exception) {
            val message = when (e) {
                is BaseRefactoringProcessor.ConflictsInTestsException -> e.messages.sorted().joinToString("\n")
                is CommonRefactoringUtil.RefactoringErrorHintException -> e.message!!
                else -> throw e
            }
            KotlinTestUtils.assertEqualsToFile(conflictFile, message)
        }
    }

    private fun getConflictFile(path: String): File {
        val suffix = getSuffix()
        val conflictFile = if (suffix != null) File("$path.messages.$suffix") else null
        return conflictFile?.takeIf { it.exists() } ?: File("$path.messages")
    }

    protected open fun getSuffix(): String? {
        return null
    }
}

internal fun markMembersInfo(file: PsiFile) {
    for ((element, info) in file.findElementsByCommentPrefix("// INFO: ")) {
        val parsedInfo = JsonParser.parseString(info).asJsonObject
        element.elementInfo = ElementInfo(
            parsedInfo["checked"]?.asBoolean ?: false,
            parsedInfo["toAbstract"]?.asBoolean ?: false
        )
    }
}

internal data class ElementInfo(val checked: Boolean, val toAbstract: Boolean)

internal var PsiElement.elementInfo: ElementInfo by NotNullableUserDataProperty(Key.create("ELEMENT_INFO"), ElementInfo(false, false))

internal fun <T : MemberInfoBase<*>> chooseMembers(members: List<T>): List<T> {
    members.forEach {
        val memberPsi = it.member.let { if (it is KtPsiClassWrapper) it.psiClass else it }
        val info = memberPsi.elementInfo
        it.isChecked = info.checked
        it.isToAbstract = info.toAbstract
    }
    return members.filter { it.isChecked }
}