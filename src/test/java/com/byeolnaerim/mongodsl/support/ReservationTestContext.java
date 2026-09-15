package com.byeolnaerim.mongodsl.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.codecs.DecoderContext;
import org.bson.conversions.Bson;
import org.reactivestreams.Subscriber;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.internal.MongoDocumentComparator;
import com.byeolnaerim.mongodsl.internal.MongoDocumentMatcher;
import com.byeolnaerim.mongodsl.internal.MongoDocumentSupport;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.reactivestreams.client.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Driver-call/lifecycle fixture, NOT an implementation of MongoDB or a differential oracle. */
public final class ReservationTestContext implements MongoExecutionContext {
    public final List<Document> rows = new CopyOnWriteArrayList<>();
    public final AtomicInteger queries = new AtomicInteger();
    public final AtomicInteger aggregates = new AtomicInteger();
    public final AtomicInteger mappings = new AtomicInteger();
    public final AtomicInteger metadataReads = new AtomicInteger();
    public final AtomicInteger sessionsClosed = new AtomicInteger();
    public final AtomicInteger physicalWatches = new AtomicInteger();
    public final AtomicInteger preImageRequests = new AtomicInteger();
    public final AtomicReference<FullDocument> fullDocumentMode = new AtomicReference<>();
    public final AtomicReference<BsonTimestamp> advancedTime = new AtomicReference<>();
    public final AtomicReference<ReadConcern> appliedReadConcern = new AtomicReference<>();
    public final AtomicReference<ReadPreference> appliedReadPreference = new AtomicReference<>();
    public final Sinks.Many<ChangeStreamDocument<Document>> events = Sinks.many().multicast().onBackpressureBuffer(256, false);
    public volatile ReadPreference readPreference = ReadPreference.primary();
    public volatile ReadConcern readConcern = ReadConcern.DEFAULT;
    public volatile boolean simpleCollation = true;
    public volatile boolean metadataFailure;
    public volatile boolean causal = true;
    public volatile int maxWireVersion = 17;
    public volatile BsonTimestamp operationTime = new BsonTimestamp(10, 0);
    public volatile Supplier<Flux<Document>> queryOverride;
    public final MongoDatabase database = proxy(MongoDatabase.class, (self, method, args) -> switch (method.getName()) {
        case "getName" -> "reservation_unit";
        case "getCodecRegistry" -> MongoClientSettings.getDefaultCodecRegistry();
        case "getReadPreference" -> readPreference;
        case "getReadConcern" -> readConcern;
        case "runCommand" -> Mono.defer(() -> {
            Document command = MongoBsonSupport.toDocument((Bson) args[0]);
            return Mono.just(command.containsKey("hello") ? new Document("maxWireVersion", maxWireVersion)
                : new Document("operationTime", operationTime));
        });
        case "listCollections" -> fluentPublisher(ListCollectionsPublisher.class, () -> {
            metadataReads.incrementAndGet();
            if (metadataFailure) return Flux.error(new IllegalStateException("listCollections denied"));
            return Flux.just(new Document("type", "collection").append("options", simpleCollation
                ? new Document() : new Document("collation", new Document("locale", "en").append("strength", 2))));
        });
        case "getCollection" -> collection((String) args[0]);
        case "watch" -> proxy(ChangeStreamPublisher.class, (publisher, operation, values) -> switch (operation.getName()) {
            case "subscribe" -> {
                physicalWatches.incrementAndGet();
                events.asFlux().subscribe(subscriber(values[0]));
                yield null;
            }
            case "fullDocument" -> { fullDocumentMode.set((FullDocument) values[0]); yield publisher; }
            case "fullDocumentBeforeChange" -> { preImageRequests.incrementAndGet(); yield publisher; }
            default -> fluentOrObject(publisher, operation.getReturnType(), operation.getName(), values);
        });
        default -> fluentOrObject(self, method.getReturnType(), method.getName(), args);
    });

    private MongoCollection<Document> collection(String name) {
        return proxy(MongoCollection.class, (self, method, args) -> switch (method.getName()) {
            case "getNamespace" -> new MongoNamespace("reservation_unit", name);
            case "getCodecRegistry" -> MongoClientSettings.getDefaultCodecRegistry();
            case "getReadPreference" -> readPreference;
            case "getReadConcern" -> readConcern;
            case "withReadConcern" -> { appliedReadConcern.set((ReadConcern) args[0]); yield self; }
            case "withReadPreference" -> { appliedReadPreference.set((ReadPreference) args[0]); yield self; }
            case "find" -> find(args);
            case "aggregate" -> fluentPublisher(AggregatePublisher.class, () -> {
                aggregates.incrementAndGet();
                return Flux.empty(); // Sharing tests only need a counted finite aggregate result.
            });
            default -> fluentOrObject(self, method.getReturnType(), method.getName(), args);
        });
    }

    private FindPublisher<Document> find(Object[] arguments) {
        AtomicReference<Bson> filter = new AtomicReference<>(new Document());
        AtomicReference<Bson> sort = new AtomicReference<>();
        AtomicInteger skip = new AtomicInteger();
        AtomicInteger limit = new AtomicInteger();
        if (arguments != null) for (Object value : arguments) if (value instanceof Bson bson) filter.set(bson);
        Supplier<Flux<Document>> result = () -> Flux.defer(() -> {
            queries.incrementAndGet();
            if (queryOverride != null) return queryOverride.get();
            Flux<Document> source = Flux.fromIterable(rows)
                .filter(row -> MongoDocumentMatcher.matches(MongoDocumentMatcher.analyze(filter.get()), row)
                    != MongoDocumentMatcher.MatchResult.NO_MATCH)
                .map(MongoDocumentSupport::copy);
            if (sort.get() != null) source = source.sort(MongoDocumentComparator.from(sort.get()).orElseThrow());
            source = source.skip(skip.get());
            return limit.get() == 0 ? source : source.take(Math.abs(limit.get()));
        });
        return proxy(FindPublisher.class, (self, method, args) -> switch (method.getName()) {
            case "filter" -> { filter.set((Bson) args[0]); yield self; }
            case "sort" -> { sort.set((Bson) args[0]); yield self; }
            case "skip" -> { skip.set((Integer) args[0]); yield self; }
            case "limit" -> { limit.set((Integer) args[0]); yield self; }
            case "first" -> result.get().next();
            case "subscribe" -> { result.get().subscribe(subscriber(args[0])); yield null; }
            default -> fluentOrObject(self, method.getReturnType(), method.getName(), args);
        });
    }

    @Override public Mono<MongoDatabase> getDatabase() { return Mono.just(database); }
    @Override public Mono<ClientSession> startSession() {
        return Mono.fromSupplier(() -> proxy(ClientSession.class, (self, method, args) -> switch (method.getName()) {
            case "isCausallyConsistent" -> causal;
            case "hasActiveTransaction" -> false;
            case "advanceOperationTime" -> { advancedTime.set((BsonTimestamp) args[0]); yield null; }
            case "getOperationTime" -> advancedTime.get();
            case "close" -> { sessionsClosed.incrementAndGet(); yield null; }
            default -> fluentOrObject(self, method.getReturnType(), method.getName(), args);
        }));
    }
    @Override public String getCollectionName(Class<?> type) { return "items"; }
    @Override public Object getId(Object source) { return ((Document) source).get("_id"); }
    @Override public Object getNative() { return this; }
    @Override public <T> T read(Class<T> type, Document source) {
        mappings.incrementAndGet();
        return type.cast(MongoDocumentSupport.copy(source));
    }
    @Override public Document write(Object source) { return MongoDocumentSupport.copy((Document) source); }

    public static ChangeStreamDocument<Document> event(String operation, String collection, Object id,
        BsonTimestamp time, Document fullDocument, Document updateDescription) {
        Document event = new Document("_id", new Document("token", System.nanoTime()))
            .append("operationType", operation)
            .append("ns", new Document("db", "reservation_unit").append("coll", collection))
            .append("documentKey", new Document("_id", id));
        if (time != null) event.append("clusterTime", time);
        if (fullDocument != null) event.append("fullDocument", fullDocument);
        if (updateDescription != null) event.append("updateDescription", updateDescription);
        return ChangeStreamDocument.createCodec(Document.class, MongoClientSettings.getDefaultCodecRegistry())
            .decode(new BsonDocumentReader(event.toBsonDocument(Document.class, MongoClientSettings.getDefaultCodecRegistry())), DecoderContext.builder().build());
    }

    @SuppressWarnings("unchecked")
    private static <T> Subscriber<T> subscriber(Object subscriber) { return (Subscriber<T>) subscriber; }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static <T> T fluentPublisher(Class<T> type, Supplier<Flux<Document>> source) {
        return proxy(type, (self, method, args) -> {
            if (method.getName().equals("subscribe")) {
                source.get().subscribe(subscriber(args[0]));
                return null;
            }
            if (method.getName().equals("first")) return source.get().next();
            return fluentOrObject(self, method.getReturnType(), method.getName(), args);
        });
    }

    private static Object fluentOrObject(Object self, Class<?> returnType, String name, Object[] args) {
        return switch (name) {
            case "equals" -> self == args[0];
            case "hashCode" -> System.identityHashCode(self);
            case "toString" -> "ReservationTestProxy";
            default -> {
                if (returnType.isInstance(self)) yield self;
                throw new UnsupportedOperationException("unexpected driver call: " + name);
            }
        };
    }
}
