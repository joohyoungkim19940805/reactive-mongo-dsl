package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.MongoDocumentUpdateApplier;
import com.mongodb.client.model.changestream.TruncatedArray;
import com.mongodb.client.model.changestream.UpdateDescription;


class MongoDocumentUpdateApplierTest {

	@Test
	void appliesUpdatedRemovedAndTruncatedFieldsToMaterializedDocument() {

		Document source = new Document( "_id", "1" )
			.append( "content", "before" )
			.append( "profile", new Document( "name", "A" ).append( "legacy", true ) )
			.append( "tags", new java.util.ArrayList<>( List.of( "a", "b", "c" ) ) );
		BsonDocument updated = BsonDocument.parse( "{content: 'after', 'profile.name': 'B'}" );
		UpdateDescription description = new UpdateDescription(
			List.of( "profile.legacy" ),
			updated,
			List.of( new TruncatedArray( "tags", 2 ) )
		);

		var result = MongoDocumentUpdateApplier.apply( source, description );
		assertTrue( result.applied() );
		assertEquals( "after", result.document().getString( "content" ) );
		assertEquals( "B", result.document().get( "profile", Document.class ).getString( "name" ) );
		assertFalse( result.document().get( "profile", Document.class ).containsKey( "legacy" ) );
		assertEquals( List.of( "a", "b" ), result.document().getList( "tags", String.class ) );
		assertEquals( "before", source.getString( "content" ), "source snapshot must not be mutated" );

	}

	@Test
	void fallsBackWhenMongoReportsDisambiguatedUpdatePaths() {

		Document source = new Document( "_id", "1" )
			.append( "a", new Document( "0", "before" ) );
		BsonDocument disambiguatedPaths = BsonDocument.parse( "{'a.0': ['a', '0']}" );
		UpdateDescription description = new UpdateDescription(
			List.of(),
			BsonDocument.parse( "{'a.0': 'after'}" ),
			List.of(),
			disambiguatedPaths
		);

		var result = MongoDocumentUpdateApplier.apply( source, description );
		assertFalse( result.applied() );
		assertEquals( "before", source.get( "a", Document.class ).getString( "0" ) );

	}

	@Test
	void fallsBackForNumericUpdatePathsInsteadOfGuessingArraySemantics() {

		Document source = new Document( "_id", "1" )
			.append( "items", new java.util.ArrayList<>( List.of( "a", "b" ) ) );
		UpdateDescription description = new UpdateDescription(
			List.of( "items.0" ),
			new BsonDocument(),
			List.of()
		);

		var result = MongoDocumentUpdateApplier.apply( source, description );
		assertFalse( result.applied() );
		assertEquals( List.of( "a", "b" ), source.getList( "items", String.class ) );

	}

}
