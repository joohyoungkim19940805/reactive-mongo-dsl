package com.byeolnaerim.mongodsl.criteria;


import static com.mongodb.client.model.Filters.all;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.elemMatch;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gt;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.near;
import static com.mongodb.client.model.Filters.nearSphere;
import static com.mongodb.client.model.Filters.nin;
import static com.mongodb.client.model.Filters.nor;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Filters.regex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;


/**
 * Converts the DSL's {@link FieldsPair} convenience values to MongoDB driver {@link Bson}
 * filters.
 * <p>The MongoDB driver remains responsible for rendering individual operators. This class only
 * adds the small amount of normalization that belongs to the DSL UX: different top-level fields
 * use MongoDB's implicit AND, and compatible range bounds on the same field are collapsed into a
 * single range document. Any other conflicting condition falls back to the driver's explicit
 * {@code $and} representation.</p>
 */
public final class FieldsPairBsonSupport {

	private static final double EARTH_RADIUS_M = 6_378_137.0;

	private static final Set<String> RANGE_OPERATORS = Set.of( "$gt", "$gte", "$lt", "$lte" );

	private FieldsPairBsonSupport() {}

	public static Bson createSingleCriteria(
		FieldsPair<?, ?> pair
	) {

		Objects.requireNonNull( pair, "pair must not be null" );
		String field = MongoFieldNameSupport.toMongoField( pair.getFieldName() );
		Object value = MongoFieldNameSupport.toMongoFieldValue( pair.getFieldName(), pair.getFieldValue() );

		return switch (pair.getQueryType()) {
			case eq -> dslFilter( eq( field, value ) );
			case notEq -> dslFilter( ne( field, value ) );
			case gt -> rangeFilter( field, "$gt", gt( field, value ) );
			case gte -> rangeFilter( field, "$gte", gte( field, value ) );
			case lt -> rangeFilter( field, "$lt", lt( field, value ) );
			case lte -> rangeFilter( field, "$lte", lte( field, value ) );
			case in -> dslFilter( in( field, requireCollection( value, "in" ) ) );
			case notIn -> dslFilter( nin( field, requireCollection( value, "notIn" ) ) );
			case all -> dslFilter( all( field, requireCollection( value, "all" ) ) );
			case like -> dslFilter( regex( field, Objects.toString( value, "" ), "i" ) );
			case regex -> dslFilter( regex( field, Objects.toString( value, "" ) ) );
			case exists -> dslFilter( exists( field, requireBoolean( value, "exists" ) ) );
			case isNull -> dslFilter( eq( field, null ) );
			case isNotNull -> dslFilter( ne( field, null ) );
			case between -> {
				Collection<?> range = requireCollection( value, "between" );

				if (range.size() != 2) { throw new IllegalArgumentException( "Field value must contain exactly two values for 'between'." ); }

				Object[] values = range.toArray();
				yield combineAnd(
					List
						.of(
							rangeFilter( field, "$gte", gte( field, values[0] ) ),
							rangeFilter( field, "$lte", lte( field, values[1] ) )
						)
				);

			}
			case near -> dslFilter( geo( field, value, false ) );
			case nearSphere -> dslFilter( geo( field, value, true ) );
			case elemMatch -> {
				List<Bson> filters = requireCollection( value, "elemMatch" )
					.stream()
					.map(
						item -> item instanceof FieldsPair<?, ?> nestedPair
							? createSingleCriteria( nestedPair )
							: null
					)
					.filter( Objects::nonNull )
					.toList();

				if (filters.isEmpty()) { throw new IllegalArgumentException( "elemMatch requires at least one nested FieldsPair." ); }

				yield dslFilter( elemMatch( field, combineAnd( filters ) ) );

			}

		};

	}

	public static Bson combine(
		Collection<? extends Bson> filters, String logicalOperator
	) {

		List<Bson> values = filters == null
			? List.of()
			: filters.stream().filter( Objects::nonNull ).map( Bson.class::cast ).toList();

		if (values.isEmpty()) { return new Document(); }

		boolean normalizable = values.stream().allMatch( DslFilter.class::isInstance );
		return switch (logicalOperator) {
			case "AND" -> values.size() == 1
				? values.get( 0 )
				: normalizable ? combineAnd( values ) : and( values );
			case "OR" -> values.size() == 1
				? values.get( 0 )
				: normalizable ? dslFilter( or( values ) ) : or( values );
			case "NOR" -> normalizable ? dslFilter( nor( values ) ) : nor( values );
			default -> throw new IllegalArgumentException( "Unsupported logical operator: " + logicalOperator );

		};

	}

	private static Bson combineAnd(
		Collection<? extends Bson> filters
	) {

		Document combined = new Document();
		Map<String, Set<String>> rangeOperators = new HashMap<>();
		List<Document> conflicts = new ArrayList<>();

		for (Bson filter : filters) {
			Document document = MongoBsonSupport.toDocument( filter );
			DslFilter dslFilter = filter instanceof DslFilter value ? value : null;
			Document conflict = new Document();

			for (Map.Entry<String, Object> entry : document.entrySet()) {
				boolean mergeableRange = dslFilter != null && dslFilter.rangeField() != null && document.size() == 1 && dslFilter.rangeField().equals( entry.getKey() ) && RANGE_OPERATORS
					.contains( dslFilter.rangeOperator() );

				if (! combined.containsKey( entry.getKey() )) {
					combined
						.put(
							entry.getKey(),
							entry.getValue() instanceof Document nested ? new Document( nested ) : entry.getValue()
						);

					if (mergeableRange) {
						rangeOperators.put( entry.getKey(), new HashSet<>( Set.of( dslFilter.rangeOperator() ) ) );

					}

					continue;

				}

				Object current = combined.get( entry.getKey() );
				Set<String> currentRangeOperators = rangeOperators.get( entry.getKey() );

				if (mergeableRange && currentRangeOperators != null && ! currentRangeOperators.contains( dslFilter.rangeOperator() ) && current instanceof Document currentOperators && entry
					.getValue() instanceof Document nextOperators) {
					currentOperators.putAll( nextOperators );
					currentRangeOperators.add( dslFilter.rangeOperator() );

				} else {
					conflict.put( entry.getKey(), entry.getValue() );

				}

			}

			if (! conflict.isEmpty()) {
				conflicts.add( conflict );

			}

		}

		if (conflicts.isEmpty()) { return dslFilter( combined ); }

		List<Bson> clauses = new ArrayList<>( conflicts.size() + 1 );

		if (! combined.isEmpty()) {
			clauses.add( combined );

		}

		clauses.addAll( conflicts );
		return dslFilter( and( clauses ) );

	}

	private static Bson dslFilter(
		Bson filter
	) {

		return new DslFilter( filter, null, null );

	}

	private static Bson rangeFilter(
		String field, String operator, Bson filter
	) {

		return new DslFilter( filter, field, operator );

	}

	private static Bson geo(
		String field, Object value, boolean sphere
	) {

		if (! (value instanceof Double[] point) || point.length < 3) {
			throw new IllegalArgumentException(
				(sphere ? "nearSphere" : "near") + " requires Double[]{longitude, latitude, maxDistance[, minDistance]}"
			);

		}

		Double maxDistance = sphere ? point[2] / EARTH_RADIUS_M : point[2];
		Double minDistance = point.length >= 4
			? sphere ? point[3] / EARTH_RADIUS_M : point[3]
			: null;

		return sphere
			? nearSphere( field, point[0], point[1], maxDistance, minDistance )
			: near( field, point[0], point[1], maxDistance, minDistance );

	}

	private record DslFilter(Bson filter, String rangeField, String rangeOperator) implements Bson {

		private DslFilter {

			Objects.requireNonNull( filter, "filter" );

			if ((rangeField == null) != (rangeOperator == null)) { throw new IllegalArgumentException( "rangeField and rangeOperator must be provided together" ); }

		}

		@Override
		public <TDocument> BsonDocument toBsonDocument(
			Class<TDocument> documentClass, CodecRegistry codecRegistry
		) {

			return filter.toBsonDocument( documentClass, codecRegistry );

		}

	}

	private static Collection<?> requireCollection(
		Object value, String operator
	) {

		if (value instanceof Collection<?> collection) { return collection; }

		throw new IllegalArgumentException( "Field value must be a collection for '" + operator + "'." );

	}

	private static boolean requireBoolean(
		Object value, String operator
	) {

		if (value instanceof Boolean bool) { return bool; }

		throw new IllegalArgumentException( "Field value must be a Boolean for '" + operator + "'." );

	}

}
