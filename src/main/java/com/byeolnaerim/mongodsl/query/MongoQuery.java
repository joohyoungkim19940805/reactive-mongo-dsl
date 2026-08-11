package com.byeolnaerim.mongodsl.query;

import com.mongodb.ReadPreference;
import java.util.Arrays;
import org.bson.Document;

/** BSON query plan containing filter, projection, sort, paging, and driver options. */
public final class MongoQuery {

    private Document filter = new Document();
    private Document projection = new Document();
    private MongoSort sort = MongoSort.unsorted();
    private long skip;
    private int limit;
    private ReadPreference readPreference;
    private Boolean allowDiskUse;

    public static MongoQuery query(MongoCriteria criteria) {
        return new MongoQuery().addCriteria(criteria);
    }

    public MongoQuery addCriteria(MongoCriteria criteria) {
        if (criteria == null || criteria.getCriteriaObject().isEmpty()) {
            return this;
        }
        if (filter.isEmpty()) {
            filter = criteria.getCriteriaObject();
        } else {
            filter = new Document("$and", Arrays.asList(filter, criteria.getCriteriaObject()));
        }
        return this;
    }

    public MongoQuery skip(long skip) { this.skip = skip; return this; }
    public MongoQuery limit(int limit) { this.limit = limit; return this; }
    public MongoQuery with(MongoSort sort) { this.sort = sort == null ? MongoSort.unsorted() : sort; return this; }
    public MongoQuery withReadPreference(ReadPreference readPreference) { this.readPreference = readPreference; return this; }
    public MongoQuery allowDiskUse(Boolean allowDiskUse) { this.allowDiskUse = allowDiskUse; return this; }
    public FieldProjection fields() { return new FieldProjection(); }

    public Document filter() { return new Document(filter); }
    public Document projection() { return new Document(projection); }
    public MongoSort sort() { return sort; }
    public long skip() { return skip; }
    public int limit() { return limit; }
    public ReadPreference readPreference() { return readPreference; }
    public Boolean allowDiskUse() { return allowDiskUse; }

    public final class FieldProjection {
        public FieldProjection exclude(String... fields) {
            if (fields != null) {
                Arrays.stream(fields)
                    .filter(field -> field != null && !field.isBlank())
                    .forEach(field -> projection.put(field, 0));
            }
            return this;
        }
    }
}
