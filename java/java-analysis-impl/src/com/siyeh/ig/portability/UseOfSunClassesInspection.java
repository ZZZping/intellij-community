/*
 * Copyright 2003-2007 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.portability;

import com.intellij.psi.*;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public final class UseOfSunClassesInspection extends BaseInspection {

  @Override
  public @NotNull String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "use.sun.classes.problem.descriptor");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new UseOfSunClassesVisitor();
  }

  private static class UseOfSunClassesVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitVariable(@NotNull PsiVariable variable) {
      super.visitVariable(variable);
      final PsiType type = variable.getType();
      final PsiType deepComponentType = type.getDeepComponentType();
      if (!(deepComponentType instanceof PsiClassType classType)) {
        return;
      }
      final @NonNls String className = classType.getCanonicalText();
      if (!className.startsWith("sun.")) {
        return;
      }
      final PsiTypeElement typeElement = variable.getTypeElement();
      if (typeElement == null) {
        return;
      }
      registerError(typeElement);
    }

    @Override
    public void visitNewExpression(
      @NotNull PsiNewExpression newExpression) {
      super.visitNewExpression(newExpression);
      final PsiType type = newExpression.getType();
      if (type == null) {
        return;
      }
      if (!(type instanceof PsiClassType classType)) {
        return;
      }
      final @NonNls String className = classType.getCanonicalText();
      if (!className.startsWith("sun.")) {
        return;
      }
      registerNewExpressionError(newExpression);
    }
  }
}