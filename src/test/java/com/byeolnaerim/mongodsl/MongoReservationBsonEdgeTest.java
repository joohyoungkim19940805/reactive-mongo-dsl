package com.byeolnaerim.mongodsl;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bson.BsonTimestamp;
import org.bson.BsonUndefined;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.internal.MongoDocumentComparator;
import com.byeolnaerim.mongodsl.internal.MongoDocumentMatcher;
import com.byeolnaerim.mongodsl.internal.MongoDocumentMatcher.MatchResult;

class MongoReservationBsonEdgeTest {
    @Test
    void nullMissingAndNinNullNeverThrowOrSilentlyMatchWrongly() {
        var equalsNull = MongoDocumentMatcher.analyze(new Document("x", null));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(equalsNull, new Document()));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(equalsNull, new Document("x", null)));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(equalsNull, new Document("x", Arrays.asList(1, null))));
        var ninNull = MongoDocumentMatcher.analyze(new Document("x", new Document("$nin", Arrays.asList((Object) null))));
        assertEquals(MatchResult.NO_MATCH, MongoDocumentMatcher.matches(ninNull, new Document()));
        assertEquals(MatchResult.NO_MATCH, MongoDocumentMatcher.matches(ninNull, new Document("x", null)));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(ninNull, new Document("x", 1)));
    }

    @Test
    void nullElemMatchAndObjectElemMatchDoNotEvaluateMissingObjectFieldsOnScalars() {
        var nullElement = MongoDocumentMatcher.analyze(new Document("x", new Document("$elemMatch", new Document("$eq", null))));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(nullElement, new Document("x", Arrays.asList((Object) null))));
        assertEquals(MatchResult.UNKNOWN, MongoDocumentMatcher.matches(
            MongoDocumentMatcher.analyze(new Document("x", new Document("$elemMatch", new Document("$eq", 1)))),
            new Document("x", List.of(List.of(1)))));
        var objectElement = MongoDocumentMatcher.analyze(new Document("x", new Document("$elemMatch", new Document("name", null))));
        assertEquals(MatchResult.NO_MATCH, MongoDocumentMatcher.matches(objectElement, new Document("x", List.of(1, 2))));
    }

    @Test
    void bsonNumericEqualityAppliesInsideLiteralAndNestedArrays() {
        var filter = MongoDocumentMatcher.analyze(new Document("x", List.of(1, 2)));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(filter, new Document("x", List.of(1L, 2D))));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(filter, new Document("x", List.of(List.of(1L, 2D)))));
    }

    @Test
    void allOnDottedPathCanMatchDifferentTerminalArrays() {
        var filter = MongoDocumentMatcher.analyze(new Document("a.b", new Document("$all", List.of(1, 2))));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(filter,
            new Document("a", List.of(new Document("b", List.of(1)), new Document("b", List.of(2))))));
    }

    @Test
    void arrayIndexUpdatesOverlapLogicalQueryPaths() {
        assertTrue(MongoDocumentMatcher.touches(List.of("items.score"), List.of("items.0.score")));
        assertTrue(MongoDocumentMatcher.touches(List.of("items.score"), List.of("items.12")));
        assertTrue(MongoDocumentMatcher.touches(List.of("groups.members.rank"), List.of("groups.1.members.2.rank")));
        assertFalse(MongoDocumentMatcher.touches(List.of("items.score"), List.of("items.0.label")));
        assertFalse(MongoDocumentMatcher.analyze(new Document("items.0.score", 1)).locallyEvaluable());
    }

    @Test
    void rangeUsesTypeBracketingAndUnicodeBinaryOrder() {
        var numeric = MongoDocumentMatcher.analyze(new Document("x", new Document("$lt", 20)));
        assertEquals(MatchResult.NO_MATCH, MongoDocumentMatcher.matches(numeric, new Document("x", "10")));
        var text = MongoDocumentMatcher.analyze(new Document("x", new Document("$lt", "\uD800\uDC00")));
        assertEquals(MatchResult.MATCH, MongoDocumentMatcher.matches(text, new Document("x", "\uE000")));
    }

    @Test
    void preciseNumericComparisonDoesNotRoundLongOrDecimalThroughDouble() {
        var comparator = MongoDocumentComparator.from(new Document("x", 1)).orElseThrow();
        assertTrue(comparator.compare(new Document("x", 9007199254740993L), new Document("x", 9007199254740992D)) > 0);
        assertTrue(comparator.compare(new Document("x", new Decimal128(new BigDecimal("0.1"))), new Document("x", 0.1D)) < 0);
        assertEquals(0, comparator.compare(new Document("x", 1L), new Document("x", 1D)));
        assertEquals(0, comparator.compare(new Document("x", Decimal128.parse("-0E-15")), new Document("x", 0)));
        assertTrue(comparator.compare(new Document("x", Double.NaN), new Document("x", Double.NEGATIVE_INFINITY)) < 0);
    }

    @Test
    void mixedScalarSortUsesBsonTypeOrderAndRejectsFractionalDirections() {
        var comparator = MongoDocumentComparator.from(new Document("x", 1)).orElseThrow();
        var values = Arrays.asList(null, 2, "a", new ObjectId("000000000000000000000001"), true, new java.util.Date(0), new BsonTimestamp(1, 0));
        var rows = new ArrayList<Document>();
        for (int i = values.size() - 1; i >= 0; i--) rows.add(new Document("x", values.get(i)));
        rows.sort(comparator);
        assertEquals(values, rows.stream().map(row -> row.get("x")).toList());
        assertTrue(MongoDocumentComparator.from(new Document("x", 1.5)).isEmpty());
    }

    @Test
    void legacyUndefinedAndAmbiguousArrayTraversalRemainUnknown() {
        var filter = MongoDocumentMatcher.analyze(new Document("x", null));
        assertEquals(MatchResult.UNKNOWN, MongoDocumentMatcher.matches(filter, new Document("x", new BsonUndefined())));
        var dotted = MongoDocumentMatcher.analyze(new Document("a.b", null));
        assertEquals(MatchResult.UNKNOWN, MongoDocumentMatcher.matches(dotted,
            new Document("a", List.of(new Document("b", 1), new Document()))));
    }
}
