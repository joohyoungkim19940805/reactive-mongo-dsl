package com.byeolnaerim.mongodsl.query;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.bson.Document;

/** BSON-backed criteria definition used by the DSL. */
public final class MongoCriteria {

    private String field;
    private Document document = new Document();

    public MongoCriteria() {}

    private MongoCriteria(String field) {
        this.field = Objects.requireNonNull(field, "field must not be null");
    }

    public static MongoCriteria where(String field) { return new MongoCriteria(field); }

    public MongoCriteria is(Object value) {
        document = new Document(field, value);
        return this;
    }

    public MongoCriteria ne(Object value) { return operator("$ne", value); }
    public MongoCriteria gt(Object value) { return operator("$gt", value); }
    public MongoCriteria gte(Object value) { return operator("$gte", value); }
    public MongoCriteria lt(Object value) { return operator("$lt", value); }
    public MongoCriteria lte(Object value) { return operator("$lte", value); }
    public MongoCriteria exists(boolean value) { return operator("$exists", value); }
    public MongoCriteria all(Collection<?> values) { return operator("$all", values); }
    public MongoCriteria in(Collection<?> values) { return operator("$in", values); }
    public MongoCriteria nin(Collection<?> values) { return operator("$nin", values); }
    public MongoCriteria regex(String pattern) { return operator("$regex", pattern); }
    public MongoCriteria regex(String pattern, String options) {
        operator("$regex", pattern);
        if (options != null && !options.isBlank()) {
            operator("$options", options);
        }
        return this;
    }

    public MongoCriteria near(double x, double y) {
        return operator("$near", List.of(x, y));
    }

    public MongoCriteria nearSphere(double x, double y) {
        return operator("$nearSphere", List.of(x, y));
    }

    public MongoCriteria maxDistance(double value) { return operator("$maxDistance", value); }
    public MongoCriteria minDistance(double value) { return operator("$minDistance", value); }

    public MongoCriteria elemMatch(MongoCriteria criteria) {
        return operator("$elemMatch", criteria.getCriteriaObject());
    }

    public MongoCriteria andOperator(MongoCriteria... criteria) { return andOperator(Arrays.asList(criteria)); }
    public MongoCriteria andOperator(Collection<MongoCriteria> criteria) { return logical("$and", criteria); }
    public MongoCriteria orOperator(MongoCriteria... criteria) { return orOperator(Arrays.asList(criteria)); }
    public MongoCriteria orOperator(Collection<MongoCriteria> criteria) { return logical("$or", criteria); }
    public MongoCriteria norOperator(MongoCriteria... criteria) { return norOperator(Arrays.asList(criteria)); }
    public MongoCriteria norOperator(Collection<MongoCriteria> criteria) { return logical("$nor", criteria); }

    public Document getCriteriaObject() {
        return new Document(document);
    }

    private MongoCriteria operator(String operator, Object value) {
        if (field == null) {
            throw new IllegalStateException("A field is required for " + operator);
        }
        Object existing = document.get(field);
        Document operators;
        if (existing instanceof Document existingDocument) {
            operators = existingDocument;
        } else {
            operators = new Document();
            document = new Document(field, operators);
        }
        operators.put(operator, value);
        return this;
    }

    private MongoCriteria logical(String operator, Collection<MongoCriteria> criteria) {
        document = new Document(
            operator,
            criteria.stream().filter(Objects::nonNull).map(MongoCriteria::getCriteriaObject).toList()
        );
        field = null;
        return this;
    }
}
