package com.byeolnaerim.mongodsl.internal.aggregation;

import org.bson.Document;

@FunctionalInterface
public interface MongoAggregationOperation {
    Document toDocument(Object context);
}
