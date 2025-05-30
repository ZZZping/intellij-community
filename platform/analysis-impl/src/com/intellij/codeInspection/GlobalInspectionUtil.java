// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.codeInspection;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.ex.GlobalInspectionContextUtil;
import com.intellij.lang.annotation.ProblemGroup;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class GlobalInspectionUtil {
  private static final String LOC_MARKER = " #loc";

  public static @NotNull String createInspectionMessage(@NotNull String message) {
    //TODO: FIXME!
    return message + LOC_MARKER;
  }

  public static void createProblem(@NotNull PsiElement elt,
                                   @NotNull HighlightInfo info,
                                   TextRange range,
                                   @Nullable ProblemGroup problemGroup,
                                   @NotNull InspectionManager manager,
                                   @NotNull ProblemDescriptionsProcessor problemDescriptionsProcessor,
                                   @NotNull GlobalInspectionContext globalContext) {
    ProblemDescriptor descriptor = createProblemDescriptor(elt, info, range, problemGroup, manager);
    problemDescriptionsProcessor.addProblemElement(
      GlobalInspectionContextUtil.retrieveRefElement(elt, globalContext),
      descriptor
    );
  }

  public static ProblemDescriptor createProblemDescriptor(@NotNull PsiElement elt,
                                                          @NotNull HighlightInfo info,
                                                          TextRange range,
                                                          @Nullable ProblemGroup problemGroup,
                                                          @NotNull InspectionManager manager) {
    List<LocalQuickFix> fixes = new ArrayList<>();
    info.findRegisteredQuickFix((descriptor, fixRange) -> {
      if (descriptor.getAction() instanceof LocalQuickFix localQuickFix) {
        fixes.add(localQuickFix);
      }
      return null;
    });
    ProblemDescriptor descriptor =
      manager.createProblemDescriptor(elt, range, createInspectionMessage(StringUtil.notNullize(info.getDescription())),
                                      HighlightInfo.convertType(info.type), false,
                                      fixes.isEmpty() ? null : fixes.toArray(LocalQuickFix.EMPTY_ARRAY));
    descriptor.setProblemGroup(problemGroup);

    return descriptor;
  }
}
