package com.byeolnaerim.mongodsl;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.MongoDocumentComparator;
import com.mongodb.client.model.Sorts;


class MongoDocumentComparatorTest {

	@Test
	void reproducesOrdinaryMultiFieldNumericSorts() {

		var comparator = MongoDocumentComparator.from( Sorts.orderBy( Sorts.descending( "priority" ), Sorts.ascending( "createdAt" ) ) );
		assertTrue( comparator.isPresent() );

		List<Document> rows = new ArrayList<>(
			List.of(
				new Document( "_id", "b" ).append( "priority", 1 ).append( "createdAt", 20 ),
				new Document( "_id", "a" ).append( "priority", 2 ).append( "createdAt", 30 ),
				new Document( "_id", "c" ).append( "priority", 2 ).append( "createdAt", 10 )
			)
		);
		rows.sort( comparator.get() );

		assertEquals( List.of( "c", "a", "b" ), rows.stream().map( row -> row.getString( "_id" ) ).toList() );

	}

	@Test
	void rejectsOpaqueSortExpressions() {

		assertTrue( MongoDocumentComparator.from( new Document( "score", new Document( "$meta", "textScore" ) ) ).isEmpty() );

	}

	@Test
	void rejectsArraySortSemanticsForLocalMaintenance() {

		Document row = new Document( "items", List.of( new Document( "score", 3 ), new Document( "score", 1 ) ) );
		assertFalse( MongoDocumentComparator.canEvaluate( Sorts.ascending( "items.score" ), row ) );

	}

}
