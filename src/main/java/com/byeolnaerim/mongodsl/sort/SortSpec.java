package com.byeolnaerim.mongodsl.sort;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import com.mongodb.client.model.Sorts;


/**
 * Parent-bound ordered MongoDB sort DSL.
 * <p>Sort components are kept in insertion order and rendered through the MongoDB driver's
 * {@link Sorts} factory. Call {@link #end()} to apply the completed sort specification and
 * return to the parent query builder.</p>
 *
 * @param <P>
 *            the parent builder type
 */
public abstract class SortSpec<P> implements Bson {

	private final P parent;

	private final List<Bson> sorts = new ArrayList<>();

	private boolean ended;

	protected SortSpec(
						P parent
	) {

		this.parent = Objects.requireNonNull( parent, "parent" );

	}

	/** Appends an ascending sort for the given fields. */
	public SortSpec<P> asc(
		String field, String... fields
	) {

		ensureOpen();
		this.sorts.add( Sorts.ascending( resolveFields( field, fields ) ) );
		return this;

	}

	/**
	 * Appends an ascending sort for enum-backed physical field names.
	 * Enum values use {@link Enum#toString()} through the shared field-name resolver.
	 */
	public SortSpec<P> asc(
		Enum<?> field, Enum<?>... fields
	) {

		ensureOpen();
		this.sorts.add( Sorts.ascending( resolveFields( field, fields ) ) );
		return this;

	}

	/** Fallback for custom field-name wrappers. Prefer {@link String} or {@link Enum} inputs. */
	public SortSpec<P> asc(
		Object field, Object... fields
	) {

		ensureOpen();
		this.sorts.add( Sorts.ascending( resolveFields( field, fields ) ) );
		return this;

	}

	/** Appends a descending sort for the given fields. */
	public SortSpec<P> desc(
		String field, String... fields
	) {

		ensureOpen();
		this.sorts.add( Sorts.descending( resolveFields( field, fields ) ) );
		return this;

	}

	/**
	 * Appends a descending sort for enum-backed physical field names.
	 * Enum values use {@link Enum#toString()} through the shared field-name resolver.
	 */
	public SortSpec<P> desc(
		Enum<?> field, Enum<?>... fields
	) {

		ensureOpen();
		this.sorts.add( Sorts.descending( resolveFields( field, fields ) ) );
		return this;

	}

	/** Fallback for custom field-name wrappers. Prefer {@link String} or {@link Enum} inputs. */
	public SortSpec<P> desc(
		Object field, Object... fields
	) {

		ensureOpen();
		this.sorts.add( Sorts.descending( resolveFields( field, fields ) ) );
		return this;

	}

	/**
	 * Appends a sort dynamically according to the given direction.
	 * Direction is matched case-insensitively against {@code asc} and {@code desc}.
	 */
	public SortSpec<P> of(
		String direction, String field, String... fields
	) {

		return of( direction, (Object) field, (Object[]) fields );

	}

	/**
	 * Appends a dynamic sort for enum-backed physical field names.
	 * Direction is matched case-insensitively against {@code asc} and {@code desc}.
	 */
	public SortSpec<P> of(
		String direction, Enum<?> field, Enum<?>... fields
	) {

		return of( direction, (Object) field, (Object[]) fields );

	}

	/**
	 * Fallback dynamic sort for custom field-name wrappers.
	 * Direction is matched case-insensitively against {@code asc} and {@code desc}.
	 */
	public SortSpec<P> of(
		String direction, Object field, Object... fields
	) {

		Objects.requireNonNull( direction, "direction" );

		if ("asc".equalsIgnoreCase( direction )) { return asc( field, fields ); }

		if ("desc".equalsIgnoreCase( direction )) { return desc( field, fields ); }

		throw new IllegalArgumentException( "Unsupported sort direction: " + direction );

	}

	/**
	 * Appends a MongoDB driver-native sort component without rewriting it.
	 *
	 * @param sort
	 *            the driver sort component
	 *
	 * @return this sort DSL
	 */
	public SortSpec<P> driver(
		Bson sort
	) {

		ensureOpen();
		this.sorts.add( Objects.requireNonNull( sort, "sort" ) );
		return this;

	}

	/** Appends MongoDB driver-native sort components in iteration order. */
	public SortSpec<P> driver(
		Collection<? extends Bson> sorts
	) {

		ensureOpen();
		Objects.requireNonNull( sorts, "sorts" ).forEach( this::driver );
		return this;

	}

	/**
	 * Applies this ordered sort specification and returns to the parent query builder.
	 * Repeated calls are idempotent.
	 *
	 * @return the parent query builder
	 */
	public final P end() {

		if (! this.ended) {
			this.ended = true;
			apply();

		}

		return this.parent;

	}

	/** Returns whether no sort component has been added. */
	public final boolean isEmpty() { return this.sorts.isEmpty(); }

	@Override
	public final <TDocument> BsonDocument toBsonDocument(
		Class<TDocument> documentClass, CodecRegistry codecRegistry
	) {

		return this.sorts.isEmpty()
			? new BsonDocument()
			: Sorts.orderBy( List.copyOf( this.sorts ) ).toBsonDocument( documentClass, codecRegistry );

	}

	/** Applies the completed sort to the owning builder. Called once by {@link #end()}. */
	protected abstract void apply();

	private void ensureOpen() {

		if (this.ended) {
			throw new IllegalStateException( "Sort specification has already ended." );

		}

	}

	private static List<String> resolveFields(
		Object field, Object[] fields
	) {

		return Stream
			.concat(
				Stream.of( Objects.requireNonNull( field, "field" ) ),
				Arrays.stream( fields == null ? new Object[0] : fields )
			)
			.map( value -> MongoFieldNameSupport.toMongoField( Objects.requireNonNull( value, "field" ) ) )
			.toList();

	}

}
