// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl.java.stubs.factories;

import com.intellij.lang.LighterAST;
import com.intellij.lang.LighterASTNode;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.impl.cache.RecordUtil;
import com.intellij.psi.impl.java.stubs.JavaStubElementType;
import com.intellij.psi.impl.java.stubs.impl.PsiNameValuePairStubImpl;
import com.intellij.psi.impl.source.tree.ElementType;
import com.intellij.psi.impl.source.tree.LightTreeUtil;
import com.intellij.psi.stubs.LightStubElementFactory;
import com.intellij.psi.stubs.StubElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class JavaNameValuePairStubFactory implements LightStubElementFactory<PsiNameValuePairStubImpl, PsiNameValuePair> {
  @Override
  public @NotNull PsiNameValuePairStubImpl createStub(@NotNull LighterAST tree, @NotNull LighterASTNode node, @NotNull StubElement<?> parentStub) {
    String name = null;
    String value = null;
    List<LighterASTNode> children = tree.getChildren(node);
    for (LighterASTNode child : children) {
      if (child.getTokenType() == JavaTokenType.IDENTIFIER) {
        name = RecordUtil.intern(tree.getCharTable(), child);
      }
      else if (ElementType.ANNOTATION_MEMBER_VALUE_BIT_SET.contains(child.getTokenType())) {
        value = LightTreeUtil.toFilteredString(tree, child, null);
      }
    }
    return new PsiNameValuePairStubImpl(parentStub, name, value);
  }

  @Override
  public PsiNameValuePair createPsi(@NotNull PsiNameValuePairStubImpl stub) {
    return JavaStubElementType.getFileStub(stub).getPsiFactory().createNameValuePair(stub);
  }
  
  @Override
  public @NotNull PsiNameValuePairStubImpl createStub(@NotNull PsiNameValuePair psi, @Nullable StubElement parentStub) {
    final String message =
      "Should not be called. Element=" + psi + "; class" + psi.getClass() + "; file=" + (psi.isValid() ? psi.getContainingFile() : "-");
    throw new UnsupportedOperationException(message);
  }
}