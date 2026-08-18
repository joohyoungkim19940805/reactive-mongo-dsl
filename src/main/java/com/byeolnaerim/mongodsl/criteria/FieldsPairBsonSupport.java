package com.byeolnaerim.mongodsl.criteria;

import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.nor;
import static com.mongodb.client.model.Filters.or;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * Converts the DSL's {@link FieldsPair} convenience values directly to MongoDB driver {@link Bson}
 * filters. Field names are treated as physical MongoDB field paths and are never rewritten from
 * Java entity metadata or framework annotations; only a path segment named {@code id} is normalized
 * to MongoDB {@code _id}.
 */
public final class FieldsPairBsonSupport {

    private static final double EARTH_RADIUS_M = 6_378_137.0;
    private static final Set<String> RANGE_OPERATORS = Set.of("$gt", "$gte", "$lt", "$lte");

    private FieldsPairBsonSupport() {}

    public static Bson createSingleCriteria(FieldsPair<?, ?> pair) {
        return createSingleCriteriaDocument(pair);
    }

    private static Document createSingleCriteriaDocument(FieldsPair<?, ?> pair) {
        Objects.requireNonNull(pair, "pair must not be null");
        String sourceField = pair.getFieldName() instanceof Enum<?> enumValue
            ? enumValue.name()
            : String.valueOf(pair.getFieldName());
        String field = MongoFieldNameSupport.toMongoField(sourceField);
        Object value = MongoFieldNameSupport.toMongoFieldValue(sourceField, pair.getFieldValue());

        return switch (pair.getQueryType()) {
            case eq -> new Document(field, value);
            case notEq -> new Document(field, new Document("$ne", value));
            case gt -> new Document(field, new Document("$gt", value));
            case gte -> new Document(field, new Document("$gte", value));
            case lt -> new Document(field, new Document("$lt", value));
            case lte -> new Document(field, new Document("$lte", value));
            case in -> new Document(field, new Document("$in", requireCollection(value, "in")));
            case notIn -> new Document(field, new Document("$nin", requireCollection(value, "notIn")));
            case all -> new Document(field, new Document("$all", requireCollection(value, "all")));
            case like -> new Document(
                field,
                new Document("$regex", Objects.toString(value, "")).append("$options", "i")
            );
            case regex -> new Document(field, new Document("$regex", Objects.toString(value, "")));
            case exists -> new Document(field, new Document("$exists", requireBoolean(value, "exists")));
            case isNull -> new Document(field, null);
            case isNotNull -> new Document(field, new Document("$ne", null));
            case between -> {
                Collection<?> range = requireCollection(value, "between");
                if (range.size() != 2) {
                    throw new IllegalArgumentException("Field value must contain exactly two values for 'between'.");
                }
                Object[] values = range.toArray();
                yield new Document(
                    field,
                    new Document("$gte", values[0]).append("$lte", values[1])
                );
            }
            case near -> geo(field, "$near", value, false);
            case nearSphere -> geo(field, "$nearSphere", value, true);
            case elemMatch -> {
                List<Document> filters = requireCollection(value, "elemMatch").stream()
                    .map(item -> item instanceof FieldsPair<?, ?> nestedPair
                        ? createSingleCriteriaDocument(nestedPair)
                        : null)
                    .filter(Objects::nonNull)
                    .toList();
                if (filters.isEmpty()) {
                    throw new IllegalArgumentException("elemMatch requires at least one nested FieldsPair.");
                }
                yield new Document(field, new Document("$elemMatch", combineAnd(filters)));
            }
        };
    }

    public static Bson combine(Collection<? extends Bson> filters, String logicalOperator) {
        List<Bson> values = filters == null ? new ArrayList<>() : new ArrayList<>(filters);
        values.removeIf(Objects::isNull);
        if (values.isEmpty()) {
            return new Document();
        }

        List<Document> documents = values.stream()
            .map(value -> value instanceof Document document ? document : null)
            .filter(Objects::nonNull)
            .toList();

        return switch (logicalOperator) {
            case "AND" -> values.size() == 1
                ? values.get(0)
                : documents.size() == values.size() ? combineAnd(documents) : and(values);
            case "OR" -> values.size() == 1
                ? values.get(0)
                : documents.size() == values.size() ? new Document("$or", documents) : or(values);
            case "NOR" -> documents.size() == values.size() ? new Document("$nor", documents) : nor(values);
            default -> throw new IllegalArgumentException("Unsupported logical operator: " + logicalOperator);
        };
    }

    private static Document combineAnd(Collection<? extends Document> filters) {
        Document combined = new Document();
        List<Document> conflicts = new ArrayList<>();

        for (Document filter : filters) {
            Document conflict = new Document();

            for (Map.Entry<String, Object> entry : filter.entrySet()) {
                if (!combined.containsKey(entry.getKey())) {
                    combined.put(
                        entry.getKey(),
                        entry.getValue() instanceof Document document ? new Document(document) : entry.getValue()
                    );
                    continue;
                }

                Object current = combined.get(entry.getKey());
                if (
                    current instanceof Document currentOperators
                    && entry.getValue() instanceof Document nextOperators
                    && !currentOperators.isEmpty()
                    && !nextOperators.isEmpty()
                    && currentOperators.keySet().stream().allMatch(RANGE_OPERATORS::contains)
                    && nextOperators.keySet().stream().allMatch(RANGE_OPERATORS::contains)
                    && nextOperators.keySet().stream().noneMatch(currentOperators::containsKey)
                ) {
                    currentOperators.putAll(nextOperators);
                } else {
                    conflict.put(entry.getKey(), entry.getValue());
                }
            }

            if (!conflict.isEmpty()) {
                conflicts.add(conflict);
            }
        }

        if (conflicts.isEmpty()) {
            return combined;
        }

        List<Document> clauses = new ArrayList<>(conflicts.size() + 1);
        if (!combined.isEmpty()) {
            clauses.add(combined);
        }
        clauses.addAll(conflicts);
        return new Document("$and", clauses);
    }

    private static Document geo(String field, String operator, Object value, boolean metersToRadians) {
        if (!(value instanceof Double[] point) || point.length < 3) {
            throw new IllegalArgumentException(
                operator + " requires Double[]{longitude, latitude, maxDistance[, minDistance]}"
            );
        }
        double max = metersToRadians ? point[2] / EARTH_RADIUS_M : point[2];
        Document condition = new Document(operator, List.of(point[0], point[1]))
            .append("$maxDistance", max);
        if (point.length >= 4) {
            condition.append("$minDistance", metersToRadians ? point[3] / EARTH_RADIUS_M : point[3]);
        }
        return new Document(field, condition);
    }

    private static Collection<?> requireCollection(Object value, String operator) {
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        throw new IllegalArgumentException("Field value must be a collection for '" + operator + "'.");
    }

    private static boolean requireBoolean(Object value, String operator) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException("Field value must be a Boolean for '" + operator + "'.");
    }

}
