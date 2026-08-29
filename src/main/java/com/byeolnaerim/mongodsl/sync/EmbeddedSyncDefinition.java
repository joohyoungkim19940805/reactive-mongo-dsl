package com.byeolnaerim.mongodsl.sync;


import java.util.List;
import java.util.Objects;


public record EmbeddedSyncDefinition(
	Class<?> sourceClass,
	Class<?> targetClass,
	String targetField,
	EmbeddedSyncCardinality cardinality,
	List<LinkFieldPair> links,
	String mapKeyField,
	EmbeddedDeletePolicy deletePolicy
) {

	public record LinkFieldPair(String fromField, String intoField, boolean intoIdAlias) {

		public LinkFieldPair {
			Objects.requireNonNull( fromField, "fromField" );
			Objects.requireNonNull( intoField, "intoField" );

		}

	}

	public EmbeddedSyncDefinition {

		Objects.requireNonNull( sourceClass, "sourceClass" );
		Objects.requireNonNull( targetClass, "targetClass" );
		Objects.requireNonNull( targetField, "targetField" );
		Objects.requireNonNull( cardinality, "cardinality" );
		links = links == null ? List.of() : List.copyOf( links );
		deletePolicy = deletePolicy == null ? EmbeddedDeletePolicy.REMOVE : deletePolicy;
		if (cardinality == EmbeddedSyncCardinality.MAP && (mapKeyField == null || mapKeyField.isBlank()))
			throw new IllegalArgumentException( "Map embedded synchronization requires mapKey(field)." );

	}

	public static EmbeddedSyncDefinition create(
		Class<?> sourceClass,
		Class<?> targetClass,
		String explicitTargetField,
		List<LinkFieldPair> links,
		String mapKeyField,
		EmbeddedDeletePolicy deletePolicy
	) {

		EmbeddedFieldMetadata metadata = EmbeddedFieldMetadata.resolve( targetClass, sourceClass, explicitTargetField );
		return new EmbeddedSyncDefinition(
			sourceClass,
			targetClass,
			metadata.mongoPath(),
			metadata.cardinality(),
			links,
			mapKeyField,
			deletePolicy
		);

	}

}
