// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.nj2k.conversions

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.j2k.ConverterContext
import org.jetbrains.kotlin.nj2k.RecursiveConversion
import org.jetbrains.kotlin.nj2k.primaryConstructor
import org.jetbrains.kotlin.nj2k.toExpression
import org.jetbrains.kotlin.nj2k.tree.*
import org.jetbrains.kotlin.nj2k.types.isArrayType

class AnnotationConversion(context: ConverterContext) : RecursiveConversion(context) {
    context(KaSession)
    override fun applyToElement(element: JKTreeElement): JKTreeElement {
        if (element !is JKAnnotation) return recurse(element)
        fixVarargsInvocation(element)
        for (parameter in element.arguments) {
            parameter.value = parameter.value.toExpression(symbolProvider)
        }

        return recurse(element)
    }

    private fun fixVarargsInvocation(annotation: JKAnnotation) {
        val newParameters =
            annotation.arguments.withIndex()
                .flatMap { (index, annotationParameter) ->
                    when {
                        annotationParameter !is JKAnnotationNameParameter
                                && annotation.isVarargsArgument(index)
                                && annotationParameter.value is JKKtAnnotationArrayInitializerExpression ->
                            (annotationParameter.value as JKKtAnnotationArrayInitializerExpression)::initializers
                                .detached()
                                .map { JKAnnotationParameterImpl(it) }

                        annotationParameter is JKAnnotationNameParameter
                                && annotation.isVarargsArgument(annotationParameter.name.value)
                                && annotationParameter.value !is JKKtAnnotationArrayInitializerExpression -> {
                            listOf(
                                JKAnnotationNameParameter(
                                    JKKtAnnotationArrayInitializerExpression(annotationParameter::value.detached()),
                                    JKNameIdentifier(annotationParameter.name.value)
                                )
                            )
                        }

                        annotationParameter is JKAnnotationNameParameter ->
                            listOf(
                                JKAnnotationNameParameter(
                                    annotationParameter::value.detached(),
                                    annotationParameter::name.detached()
                                )
                            )

                        else -> listOf(
                            JKAnnotationParameterImpl(
                                annotationParameter::value.detached()
                            )
                        )
                    }
                }
        annotation.arguments = newParameters
    }

    private fun PsiMethod.isVarArgsAnnotationMethod(isNamedArgument: Boolean) =
        isVarArgs || returnType is PsiArrayType || name == "value" && !isNamedArgument

    private fun JKParameter.isVarArgsAnnotationParameter(isNamedArgument: Boolean) =
        isVarArgs || type.type.isArrayType() || name.value == "value" && !isNamedArgument

    private fun JKAnnotation.isVarargsArgument(index: Int) =
        when (val target = classSymbol.target) {
            is JKClass -> target.primaryConstructor()
                ?.parameters
                ?.getOrNull(index)
                ?.isVarArgsAnnotationParameter(isNamedArgument = false)

            is PsiClass -> target.methods
                .getOrNull(index)
                ?.isVarArgsAnnotationMethod(isNamedArgument = false)

            else -> false
        } ?: false


    private fun JKAnnotation.isVarargsArgument(name: String): Boolean =
        when (val target = classSymbol.target) {
            is JKClass -> target.primaryConstructor()
                ?.parameters
                ?.firstOrNull { it.name.value == name }
                ?.isVarArgsAnnotationParameter(isNamedArgument = true)

            is PsiClass -> target.methods
                .firstOrNull { it.name == name }
                ?.isVarArgsAnnotationMethod(isNamedArgument = true)

            else -> false
        } ?: false
}