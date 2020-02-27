/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.inference

import org.jetbrains.kotlin.fir.FirCallResolver
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.references.builder.buildErrorNamedReference
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.diagnostics.FirUnresolvedReferenceError
import org.jetbrains.kotlin.fir.resolve.transformers.StoreNameReference
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeVariable
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.resolve.calls.components.PostponedArgumentsAnalyzer
import org.jetbrains.kotlin.resolve.calls.inference.model.CoroutinePosition
import org.jetbrains.kotlin.resolve.calls.inference.model.SimpleConstraintSystemConstraintPosition
import org.jetbrains.kotlin.resolve.calls.model.PostponedResolvedAtomMarker
import org.jetbrains.kotlin.types.model.StubTypeMarker
import org.jetbrains.kotlin.types.model.TypeVariableMarker
import org.jetbrains.kotlin.types.model.freshTypeConstructor
import org.jetbrains.kotlin.types.model.safeSubstitute

data class ReturnArgumentsAnalysisResult(
    val returnArguments: List<FirStatement>,
    val inferenceSession: FirInferenceSession?
)

interface LambdaAnalyzer {
    fun analyzeAndGetLambdaReturnArguments(
        lambdaArgument: FirAnonymousFunction,
        isSuspend: Boolean,
        receiverType: ConeKotlinType?,
        parameters: List<ConeKotlinType>,
        expectedReturnType: ConeKotlinType?, // null means, that return type is not proper i.e. it depends on some type variables
        rawReturnType: ConeKotlinType,
        stubsForPostponedVariables: Map<TypeVariableMarker, StubTypeMarker>
    ): ReturnArgumentsAnalysisResult
}


class PostponedArgumentsAnalyzer(
    private val lambdaAnalyzer: LambdaAnalyzer,
    private val components: InferenceComponents,
    private val callResolver: FirCallResolver
) {

    fun analyze(
        c: PostponedArgumentsAnalyzer.Context,
//        resolutionCallbacks: KotlinResolutionCallbacks,
        argument: PostponedResolvedAtomMarker,
        candidate: Candidate
        //diagnosticsHolder: KotlinDiagnosticsHolder
    ) {
        when (argument) {
            is ResolvedLambdaAtom ->
                analyzeLambda(c, argument, candidate/*, diagnosticsHolder*/)

//            is LambdaWithTypeVariableAsExpectedTypeAtom ->
//                analyzeLambda(
//                    c, resolutionCallbacks, argument.transformToResolvedLambda(c.getBuilder()), diagnosticsHolder
//                )

            is ResolvedCallableReferenceAtom -> processCallableReference(argument, candidate)
//
//            is ResolvedCollectionLiteralAtom -> TODO("Not supported")

            else -> error("Unexpected resolved primitive: ${argument.javaClass.canonicalName}")
        }
    }

    private fun processCallableReference(atom: ResolvedCallableReferenceAtom, candidate: Candidate) {
        if (atom.postponed) {
            callResolver.resolveCallableReference(candidate.csBuilder, atom)
        }

        val callableReferenceAccess = atom.reference
        atom.analyzed = true
        val (candidate, applicability) = atom.resultingCandidate
            ?: Pair(null, CandidateApplicability.INAPPLICABLE)

        val namedReference = when {
            candidate == null || applicability < CandidateApplicability.SYNTHETIC_RESOLVED ->
                buildErrorNamedReference {
                    source = callableReferenceAccess.source
                    diagnostic = FirUnresolvedReferenceError(callableReferenceAccess.calleeReference.name)
                }
            else -> FirNamedReferenceWithCandidate(callableReferenceAccess.source, callableReferenceAccess.calleeReference.name, candidate)
        }

        val transformedCalleeReference = callableReferenceAccess.transformCalleeReference(
            StoreNameReference,
            namedReference
        ).apply {
            if (candidate != null) {
                replaceTypeRef(buildResolvedTypeRef { type = candidate.resultingTypeForCallableReference!! })
            }
        }
    }

    private fun analyzeLambda(
        c: PostponedArgumentsAnalyzer.Context,
        lambda: ResolvedLambdaAtom,
        candidate: Candidate
        //diagnosticHolder: KotlinDiagnosticsHolder
    ) {
        val unitType = components.session.builtinTypes.unitType.type//Unit(components.session.firSymbolProvider).constructType(emptyArray(), false)
        val stubsForPostponedVariables = c.bindingStubsForPostponedVariables()
        val currentSubstitutor = c.buildCurrentSubstitutor(stubsForPostponedVariables.mapKeys { it.key.freshTypeConstructor(c) })

        fun substitute(type: ConeKotlinType) = currentSubstitutor.safeSubstitute(c, type) as ConeKotlinType

        val receiver = lambda.receiver?.let(::substitute)
        val parameters = lambda.parameters.map(::substitute)
        val rawReturnType = lambda.returnType

        val expectedTypeForReturnArguments = when {
            c.canBeProper(rawReturnType) -> substitute(rawReturnType)

            // For Unit-coercion
            c.hasUpperOrEqualUnitConstraint(rawReturnType) -> unitType

            else -> null
        }

        val (returnArguments, inferenceSession) = lambdaAnalyzer.analyzeAndGetLambdaReturnArguments(
            lambda.atom,
            lambda.isSuspend,
            receiver,
            parameters,
            expectedTypeForReturnArguments,
            rawReturnType,
            stubsForPostponedVariables
        )

        returnArguments.forEach { c.addSubsystemFromExpression(it) }

        val checkerSink: CheckerSink = CheckerSinkImpl(components)

        val subResolvedKtPrimitives = returnArguments.map {
            if (it !is FirExpression) return@map
            candidate.resolveArgumentExpression(
                c.getBuilder(),
                it,
                lambda.returnType.let(::substitute),
                lambda.atom.returnTypeRef, // TODO: proper ref
                checkerSink,
                isReceiver = false,
                isDispatch = false,
                isSafeCall = false
            )
//            resolveKtPrimitive(
//                c.getBuilder(), it, lambda.returnType.let(::substitute), diagnosticHolder, isReceiver = false
//            )
        }

        if (returnArguments.isEmpty()) {
            val lambdaReturnType = lambda.returnType.let(::substitute)
            c.getBuilder().addSubtypeConstraint(
                lambdaReturnType,
                unitType, /*LambdaArgumentConstraintPosition(lambda)*/
                SimpleConstraintSystemConstraintPosition
            )
            c.getBuilder().addSubtypeConstraint(
                unitType,
                lambdaReturnType, /*LambdaArgumentConstraintPosition(lambda)*/
                SimpleConstraintSystemConstraintPosition
            )
        }

        lambda.analyzed = true
        lambda.returnStatements = returnArguments

        if (inferenceSession != null) {
            val storageSnapshot = c.getBuilder().currentStorage()

            val postponedVariables = inferenceSession.inferPostponedVariables(lambda, storageSnapshot)

            for ((constructor, resultType) in postponedVariables) {
                val variableWithConstraints = storageSnapshot.notFixedTypeVariables[constructor] ?: continue
                val variable = variableWithConstraints.typeVariable as ConeTypeVariable

                c.getBuilder().unmarkPostponedVariable(variable)
                c.getBuilder().addEqualityConstraint(variable.defaultType, resultType, CoroutinePosition())
            }
        }
    }
}


