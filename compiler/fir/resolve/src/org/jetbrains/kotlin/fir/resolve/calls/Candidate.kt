/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.DoubleColonLHS
import org.jetbrains.kotlin.fir.resolve.ImplicitReceiverStack
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeVariable
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilder
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemOperation
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintStorage
import org.jetbrains.kotlin.resolve.calls.inference.model.NewConstraintSystemImpl
import org.jetbrains.kotlin.resolve.calls.model.PostponedResolvedAtomMarker
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind

data class CallInfo(
    val callKind: CallKind,
    val name: Name,

    val explicitReceiver: FirExpression?,
    val arguments: List<FirExpression>,
    val isSafeCall: Boolean,
    val isPotentialQualifierPart: Boolean,

    val typeArguments: List<FirTypeProjection>,
    val session: FirSession,
    val containingFile: FirFile,
    val implicitReceiverStack: ImplicitReceiverStack,

    // Four properties for callable references only
    val expectedType: ConeKotlinType? = null,
    val outerCSBuilder: ConstraintSystemBuilder? = null,
    val lhs: DoubleColonLHS? = null,
    val stubReceiver: FirExpression? = null
) {
    val argumentCount get() = arguments.size

    fun noStubReceiver(): CallInfo =
        if (stubReceiver == null) this else CallInfo(
            callKind, name, explicitReceiver, arguments,
            isSafeCall, isPotentialQualifierPart, typeArguments, session,
            containingFile, implicitReceiverStack, expectedType, outerCSBuilder, lhs, null
        )

    fun replaceWithVariableAccess(): CallInfo =
        copy(callKind = CallKind.VariableAccess, arguments = emptyList())

    fun replaceExplicitReceiver(explicitReceiver: FirExpression?): CallInfo =
        copy(explicitReceiver = explicitReceiver)

    fun withReceiverAsArgument(receiverExpression: FirExpression): CallInfo =
        copy(arguments = listOf(receiverExpression) + arguments)
}

enum class CandidateApplicability {
    HIDDEN,
    WRONG_RECEIVER,
    PARAMETER_MAPPING_ERROR,
    INAPPLICABLE,
    SYNTHETIC_RESOLVED,
    RESOLVED_LOW_PRIORITY,
    RESOLVED
}

class Candidate(
    val symbol: AbstractFirBasedSymbol<*>,
    val dispatchReceiverValue: ReceiverValue?,
    val implicitExtensionReceiverValue: ImplicitReceiverValue<*>?,
    val explicitReceiverKind: ExplicitReceiverKind,
    val bodyResolveComponents: BodyResolveComponents,
    private val baseSystem: ConstraintStorage,
    val callInfo: CallInfo
) {

    var systemInitialized: Boolean = false
    val system: NewConstraintSystemImpl by lazy {
        val system = bodyResolveComponents.inferenceComponents.createConstraintSystem()
        system.addOtherSystem(baseSystem)
        systemInitialized = true
        system
    }

    val samResolver get() = bodyResolveComponents.samResolver

    lateinit var substitutor: ConeSubstitutor
    lateinit var freshVariables: List<ConeTypeVariable>
    var resultingTypeForCallableReference: ConeKotlinType? = null
    var outerConstraintBuilderEffect: (ConstraintSystemOperation.() -> Unit)? = null
    var usesSAM: Boolean = false

    var argumentMapping: Map<FirExpression, FirValueParameter>? = null
    val postponedAtoms = mutableListOf<PostponedResolvedAtomMarker>()

    val diagnostics: MutableList<ResolutionDiagnostic> = mutableListOf()

    fun dispatchReceiverExpression(): FirExpression = when (explicitReceiverKind) {
        ExplicitReceiverKind.DISPATCH_RECEIVER, ExplicitReceiverKind.BOTH_RECEIVERS -> callInfo.explicitReceiver!!
        else -> dispatchReceiverValue?.receiverExpression ?: FirNoReceiverExpression
    }

    fun extensionReceiverExpression(): FirExpression = when (explicitReceiverKind) {
        ExplicitReceiverKind.EXTENSION_RECEIVER, ExplicitReceiverKind.BOTH_RECEIVERS -> callInfo.explicitReceiver!!
        else -> implicitExtensionReceiverValue?.receiverExpression ?: FirNoReceiverExpression
    }
}
