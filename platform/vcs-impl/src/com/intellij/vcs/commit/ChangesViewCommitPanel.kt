// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.commit

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.contextModality
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsBundle.message
import com.intellij.openapi.vcs.changes.*
import com.intellij.openapi.vcs.changes.ui.*
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode.UNVERSIONED_FILES_TAG
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager.Companion.LOCAL_CHANGES
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager.Companion.getToolWindowFor
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData.*
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.EditorTextComponent
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil.*
import com.intellij.vcsUtil.VcsUtil.getFilePath
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.concurrency.await
import javax.swing.JComponent
import kotlin.coroutines.coroutineContext
import kotlin.properties.Delegates.observable

class ChangesViewCommitPanel @ApiStatus.Internal constructor(project: Project, private val changesViewHost: ChangesViewPanel)
  : NonModalCommitPanel(project), ChangesViewCommitWorkflowUi {

  private val changesView get() = changesViewHost.changesView

  private val progressPanel = ChangesViewCommitProgressPanel(project, this, commitMessage.editorField)

  private var isHideToolWindowOnCommit = false

  private val commitActions = commitActionsPanel.createActions()
  private var rootComponent: JComponent? = null

  init {
    Disposer.register(this, commitMessage)

    setProgressComponent(progressPanel)

    for (support in EditChangelistSupport.EP_NAME.getExtensionList(project)) {
      support.installSearch(commitMessage.editorField, commitMessage.editorField)
    }

    changesView.setInclusionListener {
      //readaction is not enough
      WriteIntentReadAction.run { fireInclusionChanged() }
    }
    changesView.isShowCheckboxes = true
    ChangesViewCommitTabTitleUpdater(changesView, this, this).start()

    commitActionsPanel.isCommitButtonDefault = {
      !progressPanel.isDumbMode &&
      UIUtil.isFocusAncestor(rootComponent ?: component)
    }
  }

  fun registerRootComponent(newRootComponent: JComponent) {
    logger<ChangesViewCommitPanel>().assertTrue(rootComponent == null)
    rootComponent = newRootComponent
    commitActions.forEach { it.registerCustomShortcutSet(newRootComponent, this) }
  }

  override var editedCommit by observable<EditedCommitPresentation?>(null) { _, _, newValue ->
    ChangesViewManager.getInstanceEx(project).promiseRefresh().then {
      newValue?.let { expand(it) }
    }
  }

  override val isActive: Boolean get() = component.isVisible

  override fun activate(): Boolean {
    val toolWindow = getVcsToolWindow() ?: return false
    val contentManager = ChangesViewContentManager.getInstance(project)

    saveToolWindowState()
    changesView.isShowCheckboxes = true
    component.isVisible = true
    commitActionsPanel.isActive = true

    toolbar.updateActionsImmediately()

    contentManager.selectContent(LOCAL_CHANGES)
    toolWindow.activate({ commitMessage.requestFocusInMessage() }, false)
    return true
  }

  override fun deactivate(isOnCommit: Boolean) {
    if (isOnCommit && isHideToolWindowOnCommit) {
      getVcsToolWindow()?.hide(null)
    }

    clearToolWindowState()
    changesView.isShowCheckboxes = false
    component.isVisible = false
    commitActionsPanel.isActive = false

    toolbar.updateActionsImmediately()
  }

  private fun saveToolWindowState() {
    if (!isActive) {
      isHideToolWindowOnCommit = getVcsToolWindow()?.isVisible != true
    }
  }

  private fun clearToolWindowState() {
    isHideToolWindowOnCommit = false
  }

  private fun getVcsToolWindow(): ToolWindow? = getToolWindowFor(project, LOCAL_CHANGES)

  override fun expand(item: Any) {
    val node = changesView.findNodeInTree(item)
    node?.let { changesView.expandSafe(it) }
  }

  override fun select(item: Any) {
    val path = changesView.findNodePathInTree(item)
    path?.let { selectPath(changesView, it, false) }
  }

  override fun selectFirst(items: Collection<Any>) {
    if (items.isEmpty()) return

    val path = treePathTraverser(changesView).preOrderDfsTraversal().find { getLastUserObject(it) in items }
    path?.let { selectPath(changesView, it, false) }
  }

  override fun setCompletionContext(changeLists: List<LocalChangeList>) {
    commitMessage.setChangesSupplier(ChangeListChangesSupplier(changeLists))
  }

  override suspend fun refreshChangesViewBeforeCommit() {
    val modalityState = coroutineContext.contextModality() ?: ModalityState.nonModal()
    ChangesViewManager.getInstanceEx(project).promiseRefresh(modalityState).await()
  }

  override fun getDisplayedChanges(): List<Change> = all(changesView).userObjects(Change::class.java)
  override fun getIncludedChanges(): List<Change> = included(changesView).userObjects(Change::class.java)

  override fun getDisplayedUnversionedFiles(): List<FilePath> =
    allUnderTag(changesView, UNVERSIONED_FILES_TAG).userObjects(FilePath::class.java)

  override fun getIncludedUnversionedFiles(): List<FilePath> =
    includedUnderTag(changesView, UNVERSIONED_FILES_TAG).userObjects(FilePath::class.java)

  override var inclusionModel: InclusionModel?
    get() = changesView.inclusionModel
    set(value) {
      changesView.setInclusionModel(value)
    }

  override val commitProgressUi: CommitProgressUi get() = progressPanel

  override fun endExecution() = closeEditorPreviewIfEmpty()

  private fun closeEditorPreviewIfEmpty() {
    val changesViewManager = ChangesViewManager.getInstance(project) as? ChangesViewManager ?: return
    ChangesViewManager.getInstanceEx(project).promiseRefresh().then {
      changesViewManager.closeEditorPreview(true)
    }
  }

  override fun dispose() {
    changesView.isShowCheckboxes = false
    changesView.setInclusionListener(null)
  }
}

private class ChangesViewCommitProgressPanel(
  project: Project,
  private val commitWorkflowUi: ChangesViewCommitWorkflowUi,
  commitMessage: EditorTextComponent,
) : CommitProgressPanel(project) {

  private var oldInclusion: Set<Any> = emptySet()

  init {
    setup(commitWorkflowUi, commitMessage, JBUI.Borders.empty())
  }

  override fun inclusionChanged() {
    val newInclusion = commitWorkflowUi.inclusionModel?.getInclusion().orEmpty()

    if (oldInclusion != newInclusion) super.inclusionChanged()
    oldInclusion = newInclusion
  }
}

private class ChangesViewCommitTabTitleUpdater(tree: ChangesTree, workflowUi: CommitWorkflowUi, disposable: Disposable)
  : CommitTabTitleUpdater(tree, LOCAL_CHANGES, { message("local.changes.tab") },
                          pathsProvider = {
                            val singleRoot = ProjectLevelVcsManager.getInstance(tree.project).allVersionedRoots.singleOrNull()
                            if (singleRoot != null) listOf(getFilePath(singleRoot)) else workflowUi.getDisplayedPaths()
                          }),
    ChangesViewContentManagerListener {
  init {
    Disposer.register(disposable, this)
  }

  override fun start() {
    super.start()
    project.messageBus.connect(this).subscribe(ChangesViewContentManagerListener.TOPIC, this)
  }

  override fun toolWindowMappingChanged() = updateTab()

  override fun updateTab() {
    if (!project.isCommitToolWindowShown) return
    super.updateTab()
  }
}
