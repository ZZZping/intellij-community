// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diff.tools.util.base;

import com.intellij.diff.DiffContext;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.contents.EmptyContent;
import com.intellij.diff.contents.FileDocumentContentImpl;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.tools.util.DiffNotifications;
import com.intellij.diff.tools.util.FoldingModelSupport;
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder.TextDiffSettings;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.diff.util.DiffUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.DiffBundle;
import com.intellij.openapi.diff.impl.DiffUsageTriggerCollector;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorPopupHandler;
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.*;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;

import static com.intellij.diff.util.DiffUtil.isUserDataFlagSet;

@ApiStatus.Internal
public final class TextDiffViewerUtil {
  private static final Logger LOG = Logger.getInstance(TextDiffViewerUtil.class);

  public static @NotNull List<AnAction> createEditorPopupActions() {
    List<AnAction> result = new ArrayList<>();
    result.add(ActionManager.getInstance().getAction("CompareClipboardWithSelection"));

    result.add(Separator.getInstance());
    ContainerUtil.addAll(result, ActionManager.getInstance().getAction(IdeActions.GROUP_DIFF_EDITOR_POPUP));

    return result;
  }

  public static @NotNull FoldingModelSupport.Settings getFoldingModelSettings(@NotNull DiffContext context) {
    TextDiffSettings settings = getTextSettings(context);
    return getFoldingModelSettings(settings);
  }

  public static @NotNull FoldingModelSupport.Settings getFoldingModelSettings(@NotNull TextDiffSettings settings) {
    return new FoldingModelSupport.Settings(settings.getContextRange(), settings.isExpandByDefault());
  }


  public static @NotNull TextDiffSettings getTextSettings(@NotNull DiffContext context) {
    TextDiffSettings settings = context.getUserData(TextDiffSettings.KEY);
    if (settings == null) {
      settings = TextDiffSettings.getSettings(context.getUserData(DiffUserDataKeys.PLACE));
      context.putUserData(TextDiffSettings.KEY, settings);
      if (isUserDataFlagSet(DiffUserDataKeys.DO_NOT_IGNORE_WHITESPACES, context)) {
        settings.setIgnorePolicy(IgnorePolicy.DEFAULT);
      }
    }
    return settings;
  }

  public static boolean @NotNull [] checkForceReadOnly(@NotNull DiffContext context, @NotNull ContentDiffRequest request) {
    List<DiffContent> contents = request.getContents();
    int contentCount = contents.size();
    boolean[] result = new boolean[contentCount];

    boolean[] data = request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS);
    if (data != null && data.length != contentCount) {
      LOG.warn("Invalid FORCE_READ_ONLY_CONTENTS key value: " + request);
      data = null;
    }

    for (int i = 0; i < contents.size(); i++) {
      if (isUserDataFlagSet(DiffUserDataKeys.FORCE_READ_ONLY, contents.get(i), request, context) ||
          data != null && data[i]) {
        result[i] = true;
      }
    }

    return result;
  }

  public static void installDocumentListeners(@NotNull DocumentListener listener,
                                              @NotNull List<? extends Document> documents,
                                              @NotNull Disposable disposable) {
    for (Document document : new HashSet<>((Collection<? extends Document>)documents)) {
      document.addDocumentListener(listener, disposable);
    }
  }

  public static void checkDifferentDocuments(@NotNull ContentDiffRequest request) {
    // Actually, this should be a valid case. But it has little practical sense and will require explicit checks everywhere.
    // Some listeners will be processed once instead of 2 times, some listeners will cause illegal document modifications.
    List<DiffContent> contents = request.getContents();

    boolean sameDocuments = false;
    for (int i = 0; i < contents.size(); i++) {
      for (int j = i + 1; j < contents.size(); j++) {
        DiffContent content1 = contents.get(i);
        DiffContent content2 = contents.get(j);
        if (!(content1 instanceof DocumentContent)) continue;
        if (!(content2 instanceof DocumentContent)) continue;
        sameDocuments |= ((DocumentContent)content1).getDocument() == ((DocumentContent)content2).getDocument();
      }
    }

    if (sameDocuments) {
      StringBuilder message = new StringBuilder();
      message.append("DiffRequest with same documents detected\n");
      message.append(request).append("\n");
      for (DiffContent content : contents) {
        message.append(content).append("\n");
      }
      LOG.warn(message.toString());
    }
  }

  public static @Nullable JPanel createEqualContentsNotification(@NotNull List<? extends DocumentContent> contents) {
    boolean isPartialPreview = ContainerUtil.exists(contents, content ->
      FileDocumentManager.getInstance().isPartialPreviewOfALargeFile(content.getDocument()));
    if (isPartialPreview) return null;

    boolean equalCharsets = areEqualCharsets(contents);
    boolean equalSeparators = areEqualLineSeparators(contents);
    return DiffNotifications.createEqualContents(equalCharsets, equalSeparators);
  }

  public static boolean areEqualLineSeparators(@NotNull List<? extends DiffContent> contents) {
    return areEqualDocumentContentProperties(contents, DocumentContent::getLineSeparator);
  }

  public static boolean areEqualCharsets(@NotNull List<? extends DiffContent> contents) {
    boolean sameCharset = areEqualDocumentContentProperties(contents, DocumentContent::getCharset);
    boolean sameBOM = areEqualDocumentContentProperties(contents, DocumentContent::hasBom);
    return sameCharset && sameBOM;
  }

  private static <T> boolean areEqualDocumentContentProperties(@NotNull List<? extends DiffContent> contents,
                                                               @NotNull Function<? super DocumentContent, ? extends T> propertyGetter) {
    List<T> properties = ContainerUtil.mapNotNull(contents, (content) -> {
      if (content instanceof EmptyContent) return null;
      if (content instanceof FileDocumentContentImpl o && o.getProject() != null) {
        try (AccessToken ignore = ProjectLocator.withPreferredProject(o.getFile(), o.getProject())) {
          return propertyGetter.apply(o);
        }
      }
      return propertyGetter.fun((DocumentContent)content);
    });

    if (properties.size() < 2) return true;
    return new HashSet<>(properties).size() == 1;
  }

  public static void applyModification(@NotNull Document document1,
                                       int line1,
                                       int line2,
                                       @NotNull Document document2,
                                       int oLine1,
                                       int oLine2,
                                       boolean isLocalChangeRevert) {
    DiffUtil.applyModification(document1, line1, line2, document2, oLine1, oLine2);

    if (isLocalChangeRevert) {
      DiffUtil.clearLineModificationFlags(document1, line1, line1 + (oLine2 - oLine1));
    }
  }

  //
  // Actions
  //

  public abstract static class ComboBoxSettingAction<T> extends ComboBoxAction implements DumbAware {
    private DefaultActionGroup myActions;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Presentation presentation = e.getPresentation();
      presentation.setText(getText(getValue()));
    }

    @Override
    protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
      return getActions();
    }

    public @NotNull DefaultActionGroup getActions() {
      if (myActions == null) {
        myActions = new DefaultActionGroup();
        for (T setting : getAvailableOptions()) {
          myActions.add(new MyAction(setting));
        }
      }
      return myActions;
    }

    protected abstract @NotNull @Unmodifiable List<T> getAvailableOptions();

    protected abstract @NotNull T getValue();

    protected abstract void setValue(@NotNull T option);

    protected abstract @NotNull @Nls String getText(@NotNull T option);

    private class MyAction extends AnAction implements Toggleable, DumbAware {
      private final @NotNull T myOption;

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
      }

      @Override
      public void update(@NotNull AnActionEvent e) {
        Toggleable.setSelected(e.getPresentation(), getValue() == myOption);
      }

      MyAction(@NotNull T option) {
        super(getText(option));
        myOption = option;
      }

      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        setValue(myOption);
      }
    }
  }

  public abstract static class EnumPolicySettingAction<T extends Enum<T>> extends TextDiffViewerUtil.ComboBoxSettingAction<T> {
    private final T @NotNull [] myPolicies;

    public EnumPolicySettingAction(T @NotNull [] policies) {
      assert policies.length > 0;
      myPolicies = policies;

    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      e.getPresentation().setEnabledAndVisible(myPolicies.length > 1);
    }

    @Override
    protected @NotNull @Unmodifiable List<T> getAvailableOptions() {
      return ContainerUtil.sorted(Arrays.asList(myPolicies));
    }

    @Override
    public @NotNull T getValue() {
      T value = getStoredValue();
      if (ArrayUtil.contains(value, myPolicies)) return value;

      List<T> substitutes = getValueSubstitutes(value);
      for (T substitute : substitutes) {
        if (ArrayUtil.contains(substitute, myPolicies)) return substitute;
      }

      return myPolicies[0];
    }

    protected abstract @NotNull T getStoredValue();

    protected @NotNull List<T> getValueSubstitutes(@NotNull T value) {
      return Collections.emptyList();
    }
  }

  public static class HighlightPolicySettingAction extends EnumPolicySettingAction<HighlightPolicy> {
    protected final @NotNull TextDiffSettings mySettings;

    public HighlightPolicySettingAction(@NotNull TextDiffSettings settings,
                                        HighlightPolicy @NotNull ... policies) {
      super(policies);
      mySettings = settings;
    }

    @Override
    protected void setValue(@NotNull HighlightPolicy option) {
      if (getValue() == option) return;
      DiffUsageTriggerCollector.logToggleHighlightPolicy(option, mySettings.getPlace());
      mySettings.setHighlightPolicy(option);
    }

    @Override
    protected @NotNull HighlightPolicy getStoredValue() {
      return mySettings.getHighlightPolicy();
    }

    @Override
    protected @NotNull List<HighlightPolicy> getValueSubstitutes(@NotNull HighlightPolicy value) {
      if (value == HighlightPolicy.BY_WORD_SPLIT) {
        return Collections.singletonList(HighlightPolicy.BY_WORD);
      }
      if (value == HighlightPolicy.DO_NOT_HIGHLIGHT) {
        return Collections.singletonList(HighlightPolicy.BY_LINE);
      }
      return Collections.singletonList(HighlightPolicy.BY_WORD);
    }

    @Override
    protected @Nls @NotNull String getText(@NotNull HighlightPolicy option) {
      return option.getText();
    }
  }

  public static class IgnorePolicySettingAction extends EnumPolicySettingAction<IgnorePolicy> {
    protected final @NotNull TextDiffSettings mySettings;

    public IgnorePolicySettingAction(@NotNull TextDiffSettings settings,
                                     IgnorePolicy @NotNull ... policies) {
      super(policies);
      mySettings = settings;
    }

    @Override
    protected void setValue(@NotNull IgnorePolicy option) {
      if (getValue() == option) return;
      DiffUsageTriggerCollector.logToggleIgnorePolicy(option, mySettings.getPlace());
      mySettings.setIgnorePolicy(option);
    }

    @Override
    protected @NotNull IgnorePolicy getStoredValue() {
      return mySettings.getIgnorePolicy();
    }

    @Override
    protected @NotNull List<IgnorePolicy> getValueSubstitutes(@NotNull IgnorePolicy value) {
      if (value == IgnorePolicy.IGNORE_WHITESPACES_CHUNKS) {
        return Collections.singletonList(IgnorePolicy.IGNORE_WHITESPACES);
      }
      if (value == IgnorePolicy.FORMATTING) {
        return Collections.singletonList(IgnorePolicy.TRIM_WHITESPACES);
      }
      return Collections.singletonList(IgnorePolicy.DEFAULT);
    }

    @Override
    protected @Nls @NotNull String getText(@NotNull IgnorePolicy option) {
      return option.getText();
    }
  }

  public static class ToggleAutoScrollAction extends DumbAwareToggleAction {
    protected final @NotNull TextDiffSettings mySettings;

    public ToggleAutoScrollAction(@NotNull TextDiffSettings settings) {
      super(DiffBundle.message("synchronize.scrolling"), null, AllIcons.Actions.SynchronizeScrolling);
      mySettings = settings;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      e.getPresentation().setVisible(!mySettings.isEnableAligningChangesMode());
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
      return mySettings.isEnableSyncScroll();
    }

    @Override
    public void setSelected(AnActionEvent e, boolean state) {
      mySettings.setEnableSyncScroll(state);
    }
  }

  public static class ToggleExpandByDefaultAction extends DumbAwareToggleAction {
    protected final @NotNull TextDiffSettings mySettings;
    private final FoldingModelSupport myFoldingSupport;

    public ToggleExpandByDefaultAction(@NotNull TextDiffSettings settings, @NotNull FoldingModelSupport foldingSupport) {
      super(DiffBundle.message("collapse.unchanged.fragments"), null, AllIcons.Actions.Collapseall);
      mySettings = settings;
      myFoldingSupport = foldingSupport;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      e.getPresentation().setVisible(mySettings.getContextRange() != -1 && myFoldingSupport.isEnabled());
    }

    @Override
    public boolean isSelected(AnActionEvent e) {
      return !mySettings.isExpandByDefault();
    }

    @Override
    public void setSelected(AnActionEvent e, boolean state) {
      boolean expand = !state;
      if (mySettings.isExpandByDefault() == expand) return;
      mySettings.setExpandByDefault(expand);
      expandAll(expand);
    }

    protected void expandAll(boolean expand) {
      myFoldingSupport.expandAll(expand);
    }
  }

  public abstract static class ReadOnlyLockAction extends ToggleAction implements DumbAware {
    protected final @NotNull DiffContext myContext;
    protected final @NotNull TextDiffSettings mySettings;

    public ReadOnlyLockAction(@NotNull DiffContext context) {
      super(DiffBundle.message("disable.editing"), null, AllIcons.Diff.Lock);
      myContext = context;
      mySettings = getTextSettings(context);
    }

    protected void applyDefaults() {
      if (isVisible()) { // apply default state
        setSelected(isSelected());
      }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      if (!isVisible()) {
        e.getPresentation().setEnabledAndVisible(false);
      }
      else {
        super.update(e);
      }
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
      return isSelected();
    }

    boolean isSelected() {
      return mySettings.isReadOnlyLock();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      setSelected(state);
    }

    void setSelected(boolean state) {
      mySettings.setReadOnlyLock(state);
      doApply(state);
    }

    private boolean isVisible() {
      return myContext.getUserData(DiffUserDataKeysEx.SHOW_READ_ONLY_LOCK) == Boolean.TRUE && canEdit();
    }

    protected abstract void doApply(boolean readOnly);

    protected abstract boolean canEdit();

    protected void putEditorHint(@NotNull EditorEx editor, boolean readOnly) {
      if (readOnly) {
        EditorModificationUtil.setReadOnlyHint(editor, DiffBundle.message("editing.viewer.hint.enable.editing.text"),
                                               (e) -> {
                                                 if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                                                   setSelected(false);
                                                 }
                                               });
      }
      else {
        EditorModificationUtil.setReadOnlyHint(editor, null);
      }
    }
  }

  public static class EditorReadOnlyLockAction extends ReadOnlyLockAction {
    private final List<? extends EditorEx> myEditableEditors;

    public EditorReadOnlyLockAction(@NotNull DiffContext context, @NotNull List<? extends EditorEx> editableEditors) {
      super(context);
      myEditableEditors = editableEditors;
      applyDefaults();
    }

    @Override
    protected void doApply(boolean readOnly) {
      for (EditorEx editor : myEditableEditors) {
        editor.setViewer(readOnly);
        putEditorHint(editor, readOnly);
      }
    }

    @Override
    protected boolean canEdit() {
      return !myEditableEditors.isEmpty();
    }
  }

  public static @NotNull List<? extends EditorEx> getEditableEditors(@NotNull List<? extends EditorEx> editors) {
    return ContainerUtil.filter(editors, editor -> !editor.isViewer());
  }

  public static class EditorFontSizeSynchronizer implements PropertyChangeListener {
    private final @NotNull List<? extends EditorEx> myEditors;

    private boolean myDuringUpdate = false;

    public EditorFontSizeSynchronizer(@NotNull List<? extends EditorEx> editors) {
      myEditors = editors;
    }

    public void install(@NotNull Disposable disposable) {
      if (myEditors.size() < 2) return;
      for (EditorEx editor : myEditors) {
        editor.addPropertyChangeListener(this, disposable);
      }
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
      if (myDuringUpdate) return;

      if (!EditorEx.PROP_FONT_SIZE_2D.equals(evt.getPropertyName())) return;
      if (evt.getOldValue().equals(evt.getNewValue())) return;
      float fontSize = ((Float)evt.getNewValue()).floatValue();

      for (EditorEx editor : myEditors) {
        if (evt.getSource() != editor) updateEditor(editor, fontSize);
      }
    }

    public void updateEditor(@NotNull EditorEx editor, float fontSize) {
      try {
        myDuringUpdate = true;
        editor.setFontSize(fontSize);
      }
      finally {
        myDuringUpdate = false;
      }
    }
  }

  public static class EditorActionsPopup {
    private final @NotNull List<? extends AnAction> myEditorPopupActions;

    public EditorActionsPopup(@NotNull List<? extends AnAction> editorPopupActions) {
      myEditorPopupActions = editorPopupActions;
    }

    public void install(@NotNull List<? extends EditorEx> editors, @NotNull JComponent component) {
      DiffUtil.recursiveRegisterShortcutSet(new DefaultActionGroup(myEditorPopupActions), component, null);

      EditorPopupHandler handler = new ContextMenuPopupHandler.Simple(
        myEditorPopupActions.isEmpty() ? null : new DefaultActionGroup(myEditorPopupActions)
      );
      for (EditorEx editor : editors) {
        editor.installPopupHandler(handler);
      }
    }
  }
}
