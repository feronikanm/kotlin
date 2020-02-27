/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.deserialization

import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.addDeclarations
import org.jetbrains.kotlin.fir.declarations.builder.FirClassImplBuilder
import org.jetbrains.kotlin.fir.declarations.builder.FirSealedClassBuilder
import org.jetbrains.kotlin.fir.declarations.builder.buildEnumEntry
import org.jetbrains.kotlin.fir.declarations.impl.FirDeclarationStatusImpl
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.fir.scopes.KotlinScopeProvider
import org.jetbrains.kotlin.fir.symbols.CallableId
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.metadata.deserialization.supertypes
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.serialization.deserialization.ProtoEnumFlags
import org.jetbrains.kotlin.serialization.deserialization.getName

fun deserializeClassToSymbol(
    classId: ClassId,
    classProto: ProtoBuf.Class,
    symbol: FirRegularClassSymbol,
    nameResolver: NameResolver,
    session: FirSession,
    defaultAnnotationDeserializer: AbstractAnnotationDeserializer?,
    scopeProvider: KotlinScopeProvider,
    parentContext: FirDeserializationContext? = null,
    deserializeNestedClass: (ClassId, FirDeserializationContext) -> FirRegularClassSymbol?
) {
    val flags = classProto.flags
    val kind = Flags.CLASS_KIND.get(flags)
    val modality = ProtoEnumFlags.modality(Flags.MODALITY.get(flags))
    val status = FirDeclarationStatusImpl(
        ProtoEnumFlags.visibility(Flags.VISIBILITY.get(flags)),
        modality
    ).apply {
        isExpect = Flags.IS_EXPECT_CLASS.get(flags)
        isActual = false
        isCompanion = kind == ProtoBuf.Class.Kind.COMPANION_OBJECT
        isInner = Flags.IS_INNER.get(flags)
        isData = Flags.IS_DATA.get(classProto.flags)
        isInline = Flags.IS_INLINE_CLASS.get(classProto.flags)
    }
    val isSealed = modality == Modality.SEALED
    val classBuilder = if (isSealed) FirSealedClassBuilder() else FirClassImplBuilder()
    val context =
        parentContext?.childContext(
            classProto.typeParameterList,
            nameResolver,
            TypeTable(classProto.typeTable),
            classId.relativeClassName,
            ProtoContainer.Class(
                classProto,
                parentContext.containingDeclaration as? ProtoContainer.Class,
                classId,
                null
            )
        ) ?: FirDeserializationContext.createForClass(
            classId,
            classProto,
            nameResolver,
            session,
            defaultAnnotationDeserializer ?: FirBuiltinAnnotationDeserializer(session)
        )
    classBuilder.apply {
        this.session = session
        name = classId.shortClassName
        this.status = status
        classKind = ProtoEnumFlags.classKind(kind)
        this.scopeProvider = scopeProvider
        this.symbol = symbol

        resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
        typeParameters += context.typeDeserializer.ownTypeParameters.map { it.fir }
//        annotations += context.annotationDeserializer.loadClassAnnotations(classProto, context.nameResolver)

        val typeDeserializer = context.typeDeserializer
        val classDeserializer = context.memberDeserializer

        val superTypesDeserialized = classProto.supertypes(context.typeTable).map { supertypeProto ->
            typeDeserializer.simpleType(supertypeProto)
        }// TODO: + c.components.additionalClassPartsProvider.getSupertypes(this@DeserializedClassDescriptor)

        superTypesDeserialized.mapNotNullTo(superTypeRefs) {
            if (it == null) return@mapNotNullTo null
            buildResolvedTypeRef { type = it }
        }

        addDeclarations(classProto.functionList.map(classDeserializer::loadFunction))
        addDeclarations(classProto.propertyList.map(classDeserializer::loadProperty))

        addDeclarations(
            classProto.constructorList.map {
                classDeserializer.loadConstructor(it, this)
            }
        )

        addDeclarations(
            classProto.nestedClassNameList.mapNotNull { nestedNameId ->
                val nestedClassId = classId.createNestedClassId(Name.identifier(nameResolver.getString(nestedNameId)))
                deserializeNestedClass(nestedClassId, context)?.fir
            }
        )

        addDeclarations(
            classProto.enumEntryList.mapNotNull { enumEntryProto ->
                val enumEntryName = nameResolver.getName(enumEntryProto.name)

                val enumType = ConeClassLikeTypeImpl(symbol.toLookupTag(), emptyArray(), false)
                val property = buildEnumEntry {
                    this.session = session
                    returnTypeRef = buildResolvedTypeRef { type = enumType }
                    name = enumEntryName
                    this.symbol = FirVariableSymbol(CallableId(classId, enumEntryName))
                    this.status = FirDeclarationStatusImpl(Visibilities.PUBLIC, Modality.FINAL).apply {
                        isStatic = true
                    }
                    resolvePhase = FirResolvePhase.ANALYZED_DEPENDENCIES
                }

                property
            }
        )

        if (isSealed) {
            classProto.sealedSubclassFqNameList.mapTo((this as FirSealedClassBuilder).inheritors) {
                ClassId.fromString(nameResolver.getQualifiedClassName(it))
            }
        }
    }.build().also {
        (it.annotations as MutableList<FirAnnotationCall>) += context.annotationDeserializer.loadClassAnnotations(classProto, context.nameResolver)
    }
}
