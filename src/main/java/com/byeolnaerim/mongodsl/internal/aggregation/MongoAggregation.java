package com.byeolnaerim.mongodsl.internal.aggregation;

import com.byeolnaerim.mongodsl.aggregation.MongoAggregationOptions;
import com.byeolnaerim.mongodsl.query.MongoCriteria;
import com.byeolnaerim.mongodsl.query.MongoSort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.bson.Document;

/** BSON aggregation plan and stage factories used internally by the DSL. */
public final class MongoAggregation {

    private final List<MongoAggregationOperation> operations;
    private MongoAggregationOptions options = MongoAggregationOptions.builder().build();

    private MongoAggregation(List<MongoAggregationOperation> operations) {
        this.operations = List.copyOf(operations);
    }

    public static MongoAggregation newAggregation(Collection<MongoAggregationOperation> operations) {
        return new MongoAggregation(new ArrayList<>(operations));
    }

    public static MongoAggregation newAggregation(MongoAggregationOperation... operations) {
        return new MongoAggregation(Arrays.asList(operations));
    }

    public MongoAggregation withOptions(MongoAggregationOptions options) {
        this.options = options;
        return this;
    }

    public List<Document> pipeline() {
        return operations.stream().map(operation -> operation.toDocument(null)).toList();
    }

    public MongoAggregationOptions options() { return options; }

    public static MongoAggregationOperation match(MongoCriteria criteria) {
        return ignored -> new Document("$match", criteria.getCriteriaObject());
    }

    public static MongoAggregationOperation sort(MongoSort sort) {
        return ignored -> new Document("$sort", sort.toDocument());
    }

    public static MongoAggregationOperation skip(long skip) {
        return ignored -> new Document("$skip", skip);
    }

    public static MongoAggregationOperation limit(long limit) {
        return ignored -> new Document("$limit", limit);
    }

    public static ProjectBuilder project() { return new ProjectBuilder(); }
    public static CountBuilder count() { return new CountBuilder(); }
    public static FacetBuilder facet(MongoAggregationOperation... operations) { return new FacetBuilder(operations); }
    public static MongoAggregationOptions.Builder newAggregationOptions() { return MongoAggregationOptions.builder(); }

    public static final class ProjectBuilder implements MongoAggregationOperation {
        private final Document projection = new Document();

        public ProjectBuilder andExclude(String... fields) {
            if (fields != null) {
                Arrays.stream(fields)
                    .filter(field -> field != null && !field.isBlank())
                    .forEach(field -> projection.put(field, 0));
            }
            return this;
        }

        @Override
        public Document toDocument(Object context) {
            return new Document("$project", new Document(projection));
        }
    }

    public static final class CountBuilder {
        public MongoAggregationOperation as(String field) {
            return ignored -> new Document("$count", field);
        }
    }

    public static final class FacetBuilder implements MongoAggregationOperation {
        private final Document facets = new Document();
        private List<MongoAggregationOperation> pending;

        private FacetBuilder(MongoAggregationOperation... operations) {
            this.pending = Arrays.asList(operations);
        }

        public FacetBuilder as(String name) {
            facets.put(name, pending.stream().map(operation -> operation.toDocument(null)).toList());
            pending = List.of();
            return this;
        }

        public FacetBuilder and(MongoAggregationOperation... operations) {
            pending = Arrays.asList(operations);
            return this;
        }

        public MongoAggregationOperation asOperation(String name) {
            as(name);
            return ignored -> new Document("$facet", new Document(facets));
        }

        public MongoAggregationOperation build() {
            return this;
        }

        @Override
        public Document toDocument(Object context) {
            if (!pending.isEmpty()) {
                throw new IllegalStateException("Facet pipeline requires as(name)");
            }
            return new Document("$facet", new Document(facets));
        }
    }
}
