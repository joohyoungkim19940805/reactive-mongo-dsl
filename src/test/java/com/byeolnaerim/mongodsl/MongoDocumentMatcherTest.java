package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.MongoDocumentMatcher;
import com.byeolnaerim.mongodsl.internal.MongoDocumentMatcher.MatchResult;
import com.mongodb.client.model.Filters;


class MongoDocumentMatcherTest {

	@Test
	void matchesOrdinaryFiltersWithoutMongoRoundTrip() {

		var analysis = MongoDocumentMatcher
			.analyze(
				Filters.and(
					Filters.eq( "postId", "p1" ),
					Filters.gte( "rank", 10 ),
					Filters.in( "status", List.of( "OPEN", "PINNED" ) )
				)
			);

		assertTrue( analysis.locallyEvaluable() );
		assertEquals(
			MatchResult.MATCH,
			MongoDocumentMatcher.matches( analysis, new Document( "postId", "p1" ).append( "rank", 12 ).append( "status", "OPEN" ) )
		);
		assertEquals(
			MatchResult.NO_MATCH,
			MongoDocumentMatcher.matches( analysis, new Document( "postId", "p2" ).append( "rank", 12 ).append( "status", "OPEN" ) )
		);

	}

	@Test
	void reportsOpaqueDriverExpressionInsteadOfGuessing() {

		var analysis = MongoDocumentMatcher.analyze( new Document( "$expr", new Document( "$gt", List.of( "$a", "$b" ) ) ) );
		assertFalse( analysis.locallyEvaluable() );
		assertEquals( MatchResult.UNKNOWN, MongoDocumentMatcher.matches( analysis, new Document( "a", 2 ).append( "b", 1 ) ) );

	}

	@Test
	void detectsChangedPathsThatCanAffectMembership() {

		assertTrue( MongoDocumentMatcher.touches( List.of( "author.id" ), List.of( "author" ) ) );
		assertTrue( MongoDocumentMatcher.touches( List.of( "author" ), List.of( "author.id" ) ) );
		assertFalse( MongoDocumentMatcher.touches( List.of( "postId" ), List.of( "content" ) ) );

	}

	@Test
	void treatsMongoRegexAsOpaqueInsteadOfEmulatingPcreWithJavaRegex() {

		var directRegex = MongoDocumentMatcher.analyze( new Document( "name", new org.bson.BsonRegularExpression( "^kim", "i" ) ) );
		assertFalse( directRegex.locallyEvaluable() );
		assertEquals( MatchResult.UNKNOWN, MongoDocumentMatcher.matches( directRegex, new Document( "name", "Kim" ) ) );

		var allRegex = MongoDocumentMatcher.analyze(
			new Document( "tags", new Document( "$all", List.of( new org.bson.BsonRegularExpression( "^rea" ), "mongo" ) ) )
		);
		assertFalse( allRegex.locallyEvaluable() );
		assertEquals( MatchResult.UNKNOWN, MongoDocumentMatcher.matches( allRegex, new Document( "tags", List.of( "reactive", "mongo", "java" ) ) ) );

	}

	@Test
	void treatsEmbeddedDocumentEqualityAsOpaqueBecauseMongoIsOrderSensitive() {

		var analysis = MongoDocumentMatcher.analyze(
			new Document( "profile", new Document( "a", 1 ).append( "b", 2 ) )
		);

		assertFalse( analysis.locallyEvaluable() );
		assertEquals(
			MatchResult.UNKNOWN,
			MongoDocumentMatcher.matches( analysis, new Document( "profile", new Document( "b", 2 ).append( "a", 1 ) ) )
		);

	}

	@Test
	void treatsEmbeddedDocumentsInsideMembershipOperatorsAsOpaque() {

		var inAnalysis = MongoDocumentMatcher.analyze(
			new Document( "profiles", new Document( "$in", List.of( new Document( "a", 1 ).append( "b", 2 ) ) ) )
		);
		assertFalse( inAnalysis.locallyEvaluable() );

		var allAnalysis = MongoDocumentMatcher.analyze(
			new Document( "profiles", new Document( "$all", List.of( new Document( "a", 1 ).append( "b", 2 ) ) ) )
		);
		assertFalse( allAnalysis.locallyEvaluable() );

	}

	@Test
	void emptyAllMatchesNoDocument() {

		var analysis = MongoDocumentMatcher.analyze( new Document( "tags", new Document( "$all", List.of() ) ) );
		assertTrue( analysis.locallyEvaluable() );
		assertEquals(
			MatchResult.NO_MATCH,
			MongoDocumentMatcher.matches( analysis, new Document( "tags", List.of( "mongo" ) ) )
		);

	}

}
