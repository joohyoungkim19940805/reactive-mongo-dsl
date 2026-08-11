package com.byeolnaerim.mongodsl.aggregation;

import com.mongodb.ReadPreference;
import java.util.concurrent.TimeUnit;

/** MongoDB aggregation options collected before an aggregate publisher is created. */
public final class MongoAggregationOptions {

    private final Boolean allowDiskUse;
    private final ReadPreference readPreference;
    private final Integer batchSize;
    private final Long maxTimeMillis;
    private final String comment;

    private MongoAggregationOptions(Builder builder) {
        this.allowDiskUse = builder.allowDiskUse;
        this.readPreference = builder.readPreference;
        this.batchSize = builder.batchSize;
        this.maxTimeMillis = builder.maxTimeMillis;
        this.comment = builder.comment;
    }

    public static Builder builder() { return new Builder(); }
    public Boolean allowDiskUse() { return allowDiskUse; }
    public ReadPreference readPreference() { return readPreference; }
    public Integer batchSize() { return batchSize; }
    public Long maxTimeMillis() { return maxTimeMillis; }
    public String comment() { return comment; }

    public static final class Builder {
        private Boolean allowDiskUse;
        private ReadPreference readPreference;
        private Integer batchSize;
        private Long maxTimeMillis;
        private String comment;

        public Builder allowDiskUse(Boolean allowDiskUse) { this.allowDiskUse = allowDiskUse; return this; }
        public Builder readPreference(ReadPreference readPreference) { this.readPreference = readPreference; return this; }
        public Builder batchSize(Integer batchSize) { this.batchSize = batchSize; return this; }
        public Builder maxTime(long value, TimeUnit unit) { this.maxTimeMillis = unit.toMillis(value); return this; }
        public Builder comment(String comment) { this.comment = comment; return this; }
        public MongoAggregationOptions build() { return new MongoAggregationOptions(this); }
    }
}
