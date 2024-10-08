// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.structureView.logical

import com.intellij.openapi.extensions.ExtensionPointName

interface LogicalStructureElementsProvider<P, C> {

  fun getElements(parent: P): List<C>

  companion object {
    private const val EP_FQN = "com.intellij.lang.logicalStructureElementsProvider"

    val EP_NAME = ExtensionPointName.create<LogicalStructureElementsProvider<*, *>>(EP_FQN)

    fun <P> getProviders(p: P): Sequence<LogicalStructureElementsProvider<P, Any>> {
      return EP_NAME.extensionList.asSequence()
        .filter { it.forLogicalModelClass().isInstance(p) } as Sequence<LogicalStructureElementsProvider<P, Any>>
    }
  }
  fun forLogicalModelClass(): Class<P>
}

interface ContainerElementsProvider<P, C> : LogicalStructureElementsProvider<P, C> {
  val containerName: String?
}