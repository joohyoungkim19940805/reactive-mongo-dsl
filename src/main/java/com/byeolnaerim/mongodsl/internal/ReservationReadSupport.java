package com.byeolnaerim.mongodsl.internal;


import java.util.Optional;
import java.util.function.Function;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.reactivestreams.Publisher;
import com.byeolnaerim.mongodsl.spi.MongoExecutionContext;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.ClientSession;
import com.mongodb.reactivestreams.client.MongoDatabase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/** Read-policy and causal-fence checks used only by materialized reservations. */
public final class ReservationReadSupport {

	private ReservationReadSupport() {}

	public static final class UnsafeSessionException extends IllegalStateException {
		private static final long serialVersionUID = 1L;
		public UnsafeSessionException() {
			super( "incremental reservations require a fresh causally consistent client session" );
		}
	}

	public static Optional<String> unsupportedReason(
		MongoDatabase database, ReadPreference readPreference, ReadConcern readConcern
	) {
		ReadPreference effectivePreference = readPreference != null ? readPreference : database.getReadPreference();
		ReadConcern effectiveConcern = readConcern != null ? readConcern : database.getReadConcern();
		if (! ReadPreference.primary().equals( effectivePreference ))
			return Optional.of( "incremental reservations require primary reads; the configured readPreference is preserved in REQUERY mode" );
		if (effectiveConcern != null && effectiveConcern.getLevel() != null && ! ReadConcern.MAJORITY.equals( effectiveConcern ))
			return Optional.of( "incremental reservations require majority reads; the configured readConcern is preserved in REQUERY mode" );
		return Optional.empty();
	}

	/** One probe per active shared reservation. Unknown metadata must never mean simple collation. */
	public static Mono<Boolean> hasSimpleCollation(
		MongoDatabase database, String collectionName
	) {
		return Mono.defer( () -> Mono.from( database.listCollections().filter( Filters.eq( "name", collectionName ) ) ) )
			.map( metadata -> {
				if (! "collection".equals( metadata.getString( "type" ) ))
					return false;
				Document options = metadata.get( "options", Document.class );
				Document collation = options == null ? null : options.get( "collation", Document.class );
				return collation == null || "simple".equals( collation.getString( "locale" ) );
			} )
			.defaultIfEmpty( false )
			.onErrorReturn( false );
	}

	/**
	 * Advancing a causally consistent session makes the majority read wait for afterClusterTime.
	 * A ping watermark alone does NOT prove that a majority read has observed that watermark.
	 * The caller sets primary/majority on the finite read; no transaction is held open.
	 */
	public static <T> Flux<T> causalRead(
		MongoExecutionContext context, BsonTimestamp boundary, Function<ClientSession, ? extends Publisher<T>> read
	) {
		return Flux.usingWhen(
			context.startSession().switchIfEmpty( Mono.error( new UnsafeSessionException() ) ),
			session -> {
				if (! session.isCausallyConsistent() || session.hasActiveTransaction())
					return Flux.error( new UnsafeSessionException() );
				session.advanceOperationTime( boundary );
				return Flux.from( read.apply( session ) );
			},
			session -> Mono.fromRunnable( session::close )
		);
	}

}
