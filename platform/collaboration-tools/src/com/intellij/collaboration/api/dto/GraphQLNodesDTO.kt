// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.collaboration.api.dto

open class GraphQLNodesDTO<out T>(val nodes: List<T> = listOf()) {
  @Deprecated("Removed totalCount, use constructor without totalCount")
  constructor(nodes: List<T> = listOf(), totalCount: Int? = null) : this(nodes)
}