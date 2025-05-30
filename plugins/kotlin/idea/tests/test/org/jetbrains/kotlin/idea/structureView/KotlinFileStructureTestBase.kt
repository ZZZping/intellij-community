// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.structureView

import com.intellij.openapi.ui.Queryable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.FileStructureTestFixture
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ThrowableRunnable
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.runAll
import org.jetbrains.kotlin.test.InTextDirectivesUtils

abstract class KotlinFileStructureTestBase : KotlinLightCodeInsightFixtureTestCase() {

    protected var myPopupFixture: FileStructureTestFixture? = null
    protected val popupFixture get() = myPopupFixture!!

    protected abstract val fileExtension: String

    protected open val treeFileName: String
        get() = getFileName("tree")

    override fun setUp() {
        super.setUp()
        myPopupFixture = FileStructureTestFixture(myFixture)
    }

    protected fun configureDefault() {
        myFixture.configureByFile(getFileName(fileExtension))
    }

    public override fun tearDown() {
        runAll(
            ThrowableRunnable { Disposer.dispose(popupFixture) },
            ThrowableRunnable { myPopupFixture = null },
            ThrowableRunnable { super.tearDown() }
        )
    }

    protected open fun getFileName(ext: String): String {
        return getTestName(false) + if (StringUtil.isEmpty(ext)) "" else ".$ext"
    }

    @Suppress("unused")
    protected fun checkTree(filter: String) {
        configureDefault()
        popupFixture.update()
        popupFixture.popup.setSearchFilterForTests(filter)
        PlatformTestUtil.waitForPromise(popupFixture.popup.rebuildAndUpdate())
        popupFixture.speedSearch.findAndSelectElement(filter)
        checkResult()
    }

    protected fun checkTree() {
        configureDefault()
        popupFixture.update()
        checkResult()
    }

    protected fun checkResult() {
        val extraInfoKeys = InTextDirectivesUtils.findListWithPrefixes(myFixture.file.text, "// EXTRA_INFO_KEYS:")
        val printInfo = Queryable.PrintInfo(arrayOf("text"), (listOf("location") + extraInfoKeys).toTypedArray())
        PlatformTestUtil.waitForPromise(TreeUtil.promiseExpandAll(popupFixture.tree))
        PlatformTestUtil.waitWhileBusy(popupFixture.tree)
        val popupText = StructureViewUtil.print(popupFixture.tree, false, printInfo, null).trim { it <= ' ' }
        assertSameLinesWithFile("${testDataDirectory.path}/$treeFileName", popupText)
    }
}