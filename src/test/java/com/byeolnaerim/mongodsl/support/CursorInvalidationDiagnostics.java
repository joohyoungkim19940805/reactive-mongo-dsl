package com.byeolnaerim.mongodsl.support;


import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.ObjectId;
import com.byeolnaerim.mongodsl.ReactiveMongoDsl;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


public final class CursorInvalidationDiagnostics<K> {

	record Probe(
		String collectionName,
		ObjectId insertedId,
		String namespaceKey,
		long versionBefore,
		long traceMark,
		CompletableFuture<ChangeStreamDocument<Document>> rawMongoEvent,
		CompletableFuture<ChangeStreamDocument<Document>> sharedHubEvent
	) {}

	private record SecondaryProbeResult(
		boolean rawMongoObserved,
		String rawMongoResult,
		boolean sharedHubObserved,
		String sharedHubResult,
		long versionBefore,
		long versionAfter,
		List<DiagnosticReactiveMongoDslStateStore.TraceEvent> stateStoreTrace
	) {}

	private final MongoDatabase mongoDatabase;

	private final ReactiveMongoDsl<K> dsl;

	private final K key;

	private final DiagnosticReactiveMongoDslStateStore stateStore;

	private final Duration timeout;

	public CursorInvalidationDiagnostics(
											MongoDatabase mongoDatabase,
											ReactiveMongoDsl<K> dsl,
											K key,
											DiagnosticReactiveMongoDslStateStore stateStore,
											Duration timeout
	) {

		this.mongoDatabase = mongoDatabase;
		this.dsl = dsl;
		this.key = key;
		this.stateStore = stateStore;
		this.timeout = timeout;

	}

	public Probe begin(
		String collectionName, ObjectId insertedId, String namespaceKey, long versionBefore
	) {

		BsonTimestamp operationTime = currentMongoOperationTime();
		CompletableFuture<ChangeStreamDocument<Document>> rawMongoEvent = Flux
			.from( collection( collectionName ).watch().startAtOperationTime( operationTime ) )
			.filter( event -> isChangeForId( event, insertedId ) )
			.next()
			.toFuture();
		CompletableFuture<ChangeStreamDocument<Document>> sharedHubEvent = dsl
			.changeStreams()
			.watch( key, collectionName )
			.filter( event -> isChangeForId( event, insertedId ) )
			.next()
			.toFuture();
		return new Probe(
			collectionName,
			insertedId,
			namespaceKey,
			versionBefore,
			stateStore.mark(),
			rawMongoEvent,
			sharedHubEvent
		);

	}

	public void await(
		Probe probe
	) {

		long deadline = System.nanoTime() + timeout.toNanos();

		while (System.nanoTime() < deadline) {
			Long currentVersion = stateStore.namespaceVersion( probe.namespaceKey() ).block( timeout );
			if (currentVersion != null && currentVersion > probe.versionBefore())
				return;
			sleep( 50L );

		}

		long versionAfter = Optional.ofNullable( stateStore.namespaceVersion( probe.namespaceKey() ).block( timeout ) ).orElse( -1L );
		List<DiagnosticReactiveMongoDslStateStore.TraceEvent> firstTrace = stateStore.traceSince( probe.traceMark() );
		boolean rawMongoObserved = completedSuccessfully( probe.rawMongoEvent() );
		boolean sharedHubObserved = completedSuccessfully( probe.sharedHubEvent() );
		String rawMongoResult = futureDescription( probe.rawMongoEvent() );
		String sharedHubResult = futureDescription( probe.sharedHubEvent() );
		BsonTimestamp targetClusterTime = completedEvent( probe.rawMongoEvent() ).map( ChangeStreamDocument::getClusterTime ).orElse( null );
		BsonTimestamp latestInvalidationClusterTime = stateStore.latestInvalidationClusterTimeSince( probe.traceMark() );
		SecondaryProbeResult secondary = runSecondaryProbe( probe.collectionName(), probe.namespaceKey() );

		throw new AssertionError(
			"Timed out waiting for cursor namespace invalidation.\n" + "diagnosis=" + diagnose(
				probe.namespaceKey(),
				rawMongoObserved,
				sharedHubObserved,
				targetClusterTime,
				latestInvalidationClusterTime,
				firstTrace,
				versionAfter,
				probe.versionBefore(),
				secondary
			) + "\n" + "collection=" + probe.collectionName() + "\n" + "insertedId=" + probe.insertedId() + "\n" + "expectedNamespaceKey=" + probe.namespaceKey() + "\n" + "versionBefore=" + probe
				.versionBefore() + "\n" + "versionAfter=" + versionAfter + "\n" + "targetClusterTime=" + targetClusterTime + "\n" + "latestInvalidationClusterTime=" + latestInvalidationClusterTime + "\n" + "hubLagSeconds=" + lagSeconds(
					latestInvalidationClusterTime,
					targetClusterTime
				) + "\n" + "rawMongoChangeStream=" + rawMongoResult + "\n" + "sharedHubChangeStream=" + sharedHubResult + "\n" + "stateStoreTraceAfterOriginalInsert=" + firstTrace + "\n" + "secondaryProbe=" + secondary
		);

	}

	private SecondaryProbeResult runSecondaryProbe(
		String collectionName, String namespaceKey
	) {

		ObjectId probeId = new ObjectId();
		BsonTimestamp operationTime = currentMongoOperationTime();
		CompletableFuture<ChangeStreamDocument<Document>> rawMongoEvent = Flux
			.from( collection( collectionName ).watch().startAtOperationTime( operationTime ) )
			.filter( event -> isChangeForId( event, probeId ) )
			.next()
			.toFuture();
		CompletableFuture<ChangeStreamDocument<Document>> sharedHubEvent = dsl
			.changeStreams()
			.watch( key, collectionName )
			.filter( event -> isChangeForId( event, probeId ) )
			.next()
			.toFuture();
		long traceMark = stateStore.mark();
		long versionBefore = Optional.ofNullable( stateStore.namespaceVersion( namespaceKey ).block( timeout ) ).orElse( -1L );

		Mono
			.from(
				collection( collectionName )
					.insertOne(
						new Document( "_id", probeId ).append( "__reactiveMongoDslDiagnosticProbe", true )
					)
			)
			.block( timeout );

		long deadline = System.nanoTime() + Duration.ofSeconds( 3 ).toNanos();
		long versionAfter = versionBefore;

		while (System.nanoTime() < deadline) {
			versionAfter = Optional.ofNullable( stateStore.namespaceVersion( namespaceKey ).block( timeout ) ).orElse( -1L );
			if (versionAfter > versionBefore && rawMongoEvent.isDone() && sharedHubEvent.isDone())
				break;
			sleep( 25L );

		}

		return new SecondaryProbeResult(
			completedSuccessfully( rawMongoEvent ),
			futureDescription( rawMongoEvent ),
			completedSuccessfully( sharedHubEvent ),
			futureDescription( sharedHubEvent ),
			versionBefore,
			versionAfter,
			stateStore.traceSince( traceMark )
		);

	}

	private String diagnose(
		String expectedNamespaceKey, boolean rawMongoObserved, boolean sharedHubObserved, BsonTimestamp targetClusterTime, BsonTimestamp latestInvalidationClusterTime, List<DiagnosticReactiveMongoDslStateStore.TraceEvent> trace, long versionAfter, long versionBefore, SecondaryProbeResult secondary
	) {

		if (! rawMongoObserved)
			return "RAW_MONGO_CHANGE_STREAM_DID_NOT_OBSERVE_INSERT";
		if (! sharedHubObserved && isBefore( latestInvalidationClusterTime, targetClusterTime ))
			return "SHARED_CHANGE_STREAM_HUB_BACKLOG_BEHIND_TARGET_EVENT";
		boolean expectedInvalidationCalled = trace
			.stream()
			.anyMatch(
				event -> event.operation().startsWith( "invalidate" ) && expectedNamespaceKey.equals( event.namespaceKey() )
			);
		boolean anyInvalidationCalled = trace.stream().anyMatch( event -> event.operation().startsWith( "invalidate" ) );
		boolean checkpointSaved = trace.stream().anyMatch( event -> event.operation().startsWith( "checkpoint-save" ) );

		if (versionAfter > versionBefore)
			return "VERSION_ADVANCED_AFTER_TIMEOUT_CHECK_RACE";
		if (expectedInvalidationCalled)
			return "EXPECTED_INVALIDATION_REACHED_STATE_STORE_BUT_VERSION_DID_NOT_ADVANCE";
		if (anyInvalidationCalled)
			return "INVALIDATION_REACHED_STATE_STORE_WITH_DIFFERENT_NAMESPACE_KEY";
		if (checkpointSaved)
			return "CHANGE_STREAM_HUB_PROCESSED_EVENT_BUT_CURSOR_INVALIDATION_OBSERVER_DID_NOT_RUN";
		if (secondary.sharedHubObserved())
			return "SHARED_HUB_CAN_OBSERVE_EXPLICIT_SUBSCRIBER_PROBE_BUT_INTERNAL_KEEPER_OR_OBSERVER_PATH_DID_NOT_PROCESS_ORIGINAL_EVENT";
		if (secondary.rawMongoObserved())
			return "RAW_MONGO_WORKS_BUT_SHARED_CHANGE_STREAM_HUB_DID_NOT_DELIVER_EVENT";
		return "NO_CHANGE_STREAM_STAGE_CONFIRMED_EVENT_PROCESSING";

	}

	private Optional<ChangeStreamDocument<Document>> completedEvent(
		CompletableFuture<ChangeStreamDocument<Document>> future
	) {

		if (! completedSuccessfully( future ))
			return Optional.empty();
		return Optional.ofNullable( future.getNow( null ) );

	}

	private boolean isBefore(
		BsonTimestamp left, BsonTimestamp right
	) {

		if (left == null || right == null)
			return false;
		int seconds = Integer.compareUnsigned( left.getTime(), right.getTime() );
		return seconds < 0 || seconds == 0 && Integer.compareUnsigned( left.getInc(), right.getInc() ) < 0;

	}

	private long lagSeconds(
		BsonTimestamp processed, BsonTimestamp target
	) {

		if (processed == null || target == null)
			return -1L;
		return Integer.toUnsignedLong( target.getTime() ) - Integer.toUnsignedLong( processed.getTime() );

	}

	private BsonTimestamp currentMongoOperationTime() {

		Document result = Mono.from( mongoDatabase.runCommand( new Document( "ping", 1 ) ) ).block( timeout );
		if (result == null)
			throw new AssertionError( "MongoDB ping returned null while preparing diagnostic Change Stream probe" );
		Object operationTime = result.get( "operationTime" );
		if (operationTime instanceof BsonTimestamp timestamp)
			return timestamp;
		Object clusterTime = result.get( "$clusterTime" );
		if (clusterTime instanceof Document clusterTimeDocument && clusterTimeDocument.get( "clusterTime" ) instanceof BsonTimestamp timestamp)
			return timestamp;
		if (clusterTime instanceof BsonDocument clusterTimeDocument && clusterTimeDocument.get( "clusterTime" ) instanceof BsonTimestamp timestamp)
			return timestamp;
		throw new AssertionError( "MongoDB ping did not expose operationTime/$clusterTime for diagnostic Change Stream probe: " + result );

	}

	private MongoCollection<Document> collection(
		String collectionName
	) {

		return mongoDatabase.getCollection( collectionName );

	}

	private static boolean isChangeForId(
		ChangeStreamDocument<Document> event, ObjectId id
	) {

		if (event == null || event.getDocumentKey() == null || ! event.getDocumentKey().containsKey( "_id" ))
			return false;

		try {
			return id.equals( event.getDocumentKey().getObjectId( "_id" ).getValue() );

		} catch (RuntimeException ignored) {
			return false;

		}

	}

	private static boolean completedSuccessfully(
		CompletableFuture<?> future
	) {

		return future != null && future.isDone() && ! future.isCompletedExceptionally() && ! future.isCancelled();

	}

	private static String futureDescription(
		CompletableFuture<ChangeStreamDocument<Document>> future
	) {

		if (future == null)
			return "null";
		if (! future.isDone())
			return "PENDING";
		if (future.isCancelled())
			return "CANCELLED";

		try {
			ChangeStreamDocument<Document> event = future.join();
			return event == null
				? "COMPLETED_NULL"
				: "OBSERVED{operation=" + event.getOperationType() + ", namespace=" + event.getNamespace() + ", documentKey=" + event.getDocumentKey() + ", clusterTime=" + event
					.getClusterTime() + "}";

		} catch (CompletionException error) {
			Throwable cause = error.getCause() == null ? error : error.getCause();
			return "FAILED{" + cause.getClass().getName() + ": " + cause.getMessage() + "}";

		}

	}

	private static void sleep(
		long millis
	) {

		try {
			Thread.sleep( millis );

		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new AssertionError( error );

		}

	}

}
