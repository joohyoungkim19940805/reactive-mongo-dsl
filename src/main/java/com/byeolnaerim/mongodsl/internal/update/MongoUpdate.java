package com.byeolnaerim.mongodsl.internal.update;

import java.util.List;
import org.bson.Document;

/** Classic MongoDB update document builder. */
public final class MongoUpdate implements MongoUpdateDefinition {
    private final Document update = new Document();

    public MongoUpdate inc(String field, Number value) { return operator("$inc", field, value); }
    public MongoUpdate set(String field, Object value) { return operator("$set", field, value); }
    public MongoUpdate setOnInsert(String field, Object value) { return operator("$setOnInsert", field, value); }
    public MongoUpdate unset(String field) { return operator("$unset", field, ""); }
    public MongoUpdate push(String field, Object value) { return operator("$push", field, value); }
    public MongoUpdate addToSet(String field, Object value) { return operator("$addToSet", field, value); }
    public MongoUpdate pull(String field, Object value) { return operator("$pull", field, value); }

    private MongoUpdate operator(String operator, String field, Object value) {
        Document values = update.get(operator, Document.class);
        if (values == null) {
            values = new Document();
            update.put(operator, values);
        }
        values.put(field, value);
        return this;
    }

    public Document getUpdateObject() { return new Document(update); }
    @Override public boolean isPipeline() { return false; }
    @Override public Document document() { return getUpdateObject(); }
    @Override public List<Document> pipeline() { return List.of(); }
}
