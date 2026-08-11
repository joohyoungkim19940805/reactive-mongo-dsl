package com.byeolnaerim.mongodsl.internal.update;

import com.byeolnaerim.mongodsl.internal.aggregation.MongoAggregationOperation;
import java.util.Collection;
import java.util.List;
import org.bson.Document;

/** Aggregation-pipeline update definition. */
public final class MongoAggregationUpdate implements MongoUpdateDefinition {
    private final List<Document> pipeline;
    private MongoAggregationUpdate(List<Document> pipeline) { this.pipeline = List.copyOf(pipeline); }
    public static MongoAggregationUpdate from(Collection<MongoAggregationOperation> operations) {
        return new MongoAggregationUpdate(operations.stream().map(operation -> operation.toDocument(null)).toList());
    }
    @Override public boolean isPipeline() { return true; }
    @Override public Document document() { return new Document(); }
    @Override public List<Document> pipeline() { return pipeline; }
}
