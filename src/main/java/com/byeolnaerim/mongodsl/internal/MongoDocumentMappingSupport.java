package com.byeolnaerim.mongodsl.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.bson.Document;

/** Internal helpers for applying execution-context field mappings to BSON plans. */
public final class MongoDocumentMappingSupport {

    private MongoDocumentMappingSupport() {}

    public static Document mapFilter(Document source, Function<String, String> fieldMapper) {
        Document mapped = new Document();
        source.forEach((key, value) -> {
            if (key.startsWith("$")) {
                mapped.put(key, mapLogicalValue(key, value, fieldMapper));
            } else {
                mapped.put(fieldMapper.apply(key), mapFieldConditionValue(value, fieldMapper));
            }
        });
        return mapped;
    }

    public static Document mapFieldDocument(Document source, Function<String, String> fieldMapper) {
        Document mapped = new Document();
        source.forEach((key, value) -> mapped.put(fieldMapper.apply(key), mapExpressionValue(value, fieldMapper)));
        return mapped;
    }

    public static Document mapUpdate(Document source, Function<String, String> fieldMapper) {
        Document mapped = new Document();
        source.forEach((operator, value) -> {
            if (operator.startsWith("$") && value instanceof Document document) {
                mapped.put(operator, mapFieldDocument(document, fieldMapper));
            } else {
                mapped.put(operator, mapExpressionValue(value, fieldMapper));
            }
        });
        return mapped;
    }

    public static List<Document> mapPipeline(List<Document> pipeline, Function<String, String> fieldMapper) {
        return mapPipeline(pipeline, filter -> mapFilter(filter, fieldMapper), fieldMapper);
    }

    public static List<Document> mapPipeline(
        List<Document> pipeline,
        Function<Document, Document> filterMapper,
        Function<String, String> fieldMapper
    ) {
        List<Document> mapped = new ArrayList<>(pipeline.size());
        for (Document stage : pipeline) {
            if (stage.size() != 1) {
                mapped.add(new Document(stage));
                continue;
            }

            String operator = stage.keySet().iterator().next();
            Object value = stage.get(operator);
            Object mappedValue = switch (operator) {
                case "$match" -> value instanceof Document document ? filterMapper.apply(document) : value;
                case "$sort" -> value instanceof Document document ? mapFieldDocument(document, fieldMapper) : value;
                case "$project" -> value instanceof Document document ? mapProjection(document, fieldMapper) : value;
                case "$set", "$addFields" -> value instanceof Document document ? mapFieldDocument(document, fieldMapper) : value;
                case "$unset" -> mapUnset(value, fieldMapper);
                case "$group", "$replaceWith", "$replaceRoot" -> mapExpressionValue(value, fieldMapper);
                case "$facet" -> value instanceof Document document ? mapFacet(document, filterMapper, fieldMapper) : value;
                case "$lookup" -> value instanceof Document document ? mapLookup(document, fieldMapper) : value;
                default -> value;
            };
            mapped.add(new Document(operator, mappedValue));
        }
        return mapped;
    }

    private static Object mapLogicalValue(String operator, Object value, Function<String, String> fieldMapper) {
        if (("$and".equals(operator) || "$or".equals(operator) || "$nor".equals(operator)) && value instanceof List<?> list) {
            return list.stream()
                .map(item -> item instanceof Document document ? mapFilter(document, fieldMapper) : item)
                .toList();
        }
        return mapExpressionValue(value, fieldMapper);
    }

    private static Object mapFieldConditionValue(Object value, Function<String, String> fieldMapper) {
        if (!(value instanceof Document document)) {
            return value;
        }
        Document mapped = new Document();
        document.forEach((operator, nestedValue) -> {
            if ("$elemMatch".equals(operator) && nestedValue instanceof Document nestedDocument) {
                mapped.put(operator, mapFilter(nestedDocument, fieldMapper));
            } else if (("$and".equals(operator) || "$or".equals(operator) || "$nor".equals(operator)) && nestedValue instanceof List<?> list) {
                mapped.put(operator, list.stream()
                    .map(item -> item instanceof Document nestedDocument ? mapFilter(nestedDocument, fieldMapper) : item)
                    .toList());
            } else {
                mapped.put(operator, mapExpressionValue(nestedValue, fieldMapper));
            }
        });
        return mapped;
    }

    private static Document mapProjection(Document source, Function<String, String> fieldMapper) {
        Document mapped = new Document();
        source.forEach((key, value) -> {
            String mappedKey = value instanceof Number || value instanceof Boolean
                ? fieldMapper.apply(key)
                : key;
            mapped.put(mappedKey, mapExpressionValue(value, fieldMapper));
        });
        return mapped;
    }

    private static Object mapUnset(Object value, Function<String, String> fieldMapper) {
        if (value instanceof String field) {
            return fieldMapper.apply(field);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item instanceof String field ? fieldMapper.apply(field) : item).toList();
        }
        return value;
    }

    private static Document mapFacet(
        Document source,
        Function<Document, Document> filterMapper,
        Function<String, String> fieldMapper
    ) {
        Document mapped = new Document();
        source.forEach((name, value) -> {
            if (value instanceof List<?> list) {
                List<Document> stages = list.stream()
                    .filter(Document.class::isInstance)
                    .map(Document.class::cast)
                    .toList();
                mapped.put(name, mapPipeline(stages, filterMapper, fieldMapper));
            } else {
                mapped.put(name, value);
            }
        });
        return mapped;
    }

    private static Document mapLookup(Document source, Function<String, String> fieldMapper) {
        Document mapped = new Document(source);
        Object localField = source.get("localField");
        if (localField instanceof String field) {
            mapped.put("localField", fieldMapper.apply(field));
        }
        Object let = source.get("let");
        if (let instanceof Document document) {
            Document mappedLet = new Document();
            document.forEach((key, value) -> mappedLet.put(key, mapExpressionValue(value, fieldMapper)));
            mapped.put("let", mappedLet);
        }
        // foreignField and lookup sub-pipeline belong to the foreign collection and must not
        // be remapped with the source entity's metadata. Integrations can override the full
        // aggregation mapping hook when foreign-side mapping is required.
        return mapped;
    }

    private static Object mapExpressionValue(Object value, Function<String, String> fieldMapper) {
        if (value instanceof String stringValue) {
            if (stringValue.startsWith("$$")) {
                return stringValue;
            }
            if (stringValue.startsWith("$") && stringValue.length() > 1) {
                return "$" + fieldMapper.apply(stringValue.substring(1));
            }
            return stringValue;
        }
        if (value instanceof Document document) {
            Document mapped = new Document();
            document.forEach((key, nestedValue) -> mapped.put(key, mapExpressionValue(nestedValue, fieldMapper)));
            return mapped;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> mapExpressionValue(item, fieldMapper)).toList();
        }
        return value;
    }
}
