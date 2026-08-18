package com.byeolnaerim.mongodsl.lookup;


import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition;
import com.byeolnaerim.mongodsl.internal.MongoFieldNameSupport;
import com.byeolnaerim.mongodsl.sort.SortSpec;
import com.mongodb.client.model.Aggregates;


/**
 * Lookup-specific convenience specification used by {@code ReactiveMongoDsl}.
 * <p>Every field path is a physical MongoDB field path. The DSL never rewrites it from Java
 * property metadata except the conventional {@code id -> _id} field-path normalization. Raw
 * pipeline/outer stages use the MongoDB driver's {@link Bson} type so new
 * MongoDB aggregation features do not require a DSL-side replacement abstraction.</p>
 */
public final class LookupSpec {

	private List<Bson> outerStages = List.of();

	private String as;

	private String localField;

	private String foreignField;

	private Document letDoc = new Document();

	private List<Bson> pipelineDocs = List.of();

	private boolean unwind;

	private boolean preserveNullAndEmptyArrays;

	private LookupSpec() {}

	public List<Bson> getOuterStages() { return outerStages; }

	public String getAs() { return as; }

	public String getLocalField() { return localField; }

	public String getForeignField() { return foreignField; }

	public Document getLetDoc() { return letDoc; }

	public List<Bson> getPipelineDocs() { return pipelineDocs; }

	public boolean isUnwind() { return unwind; }

	public boolean isPreserveNullAndEmptyArrays() { return preserveNullAndEmptyArrays; }

	public static Builder builder() {

		return new Builder();

	}

	public static final class Builder {

		private final LookupSpec spec = new LookupSpec();

		private final List<Bson> outerStages = new ArrayList<>();

		private final Document letDoc = new Document();

		private final List<Bson> pipeline = new ArrayList<>();

		private final List<Document> whereExprs = new ArrayList<>();

		private int varSeq;

		public Builder outerStage(
			Bson stage
		) {

			if (stage != null) {
				outerStages.add( stage );

			}

			return this;

		}

		public Builder outerStages(
			Collection<? extends Bson> stages
		) {

			if (stages != null) {
				stages.stream().filter( java.util.Objects::nonNull ).forEach( outerStages::add );

			}

			return this;

		}

		public Builder outerMatchExpr(
			Document expr
		) {

			if (expr != null) {
				outerStages.add( Aggregates.match( new Document( "$expr", expr ) ) );

			}

			return this;

		}

		public Builder as(
			String as
		) {

			spec.as = as;
			return this;

		}

		public Builder localField(
			String localField
		) {

			spec.localField = MongoFieldNameSupport.toMongoField( localField );
			return this;

		}

		public Builder foreignField(
			String foreignField
		) {

			spec.foreignField = MongoFieldNameSupport.toMongoField( foreignField );
			return this;

		}

		public Builder bindConditionFields(
			String leftFieldPath, Condition cond, String rightFieldPath
		) {

			String var = nextVar();
			letDoc.put( var, "$" + MongoFieldNameSupport.toMongoField( leftFieldPath ) );
			addConditionExpr( cond, "$$" + var, rightFieldPath );
			return this;

		}

		public Builder bindConditionConst(
			Object constValue, Condition cond, String rightFieldPath
		) {

			addConditionExpr( cond, MongoFieldNameSupport.toMongoFieldValue( rightFieldPath, constValue ), rightFieldPath );
			return this;

		}

		public Builder bindConditionFieldsLeftToObjectId(
			String leftFieldPath, Condition cond, String rightFieldPath
		) {

			String var = nextVar();
			letDoc
				.put(
					var,
					new Document(
						"$convert",
						new Document( "input", "$" + MongoFieldNameSupport.toMongoField( leftFieldPath ) )
							.append( "to", "objectId" )
							.append( "onError", null )
							.append( "onNull", null )
					)
				);
			addConditionExpr( cond, "$$" + var, rightFieldPath );
			return this;

		}

		public Builder bindConditionBetween(
			Object lowInclusive, Object highInclusive, String rightFieldPath
		) {

			whereExprs
				.add(
					new Document(
						"$and",
						List
							.of(
								binary( "$gte", "$" + MongoFieldNameSupport.toMongoField( rightFieldPath ), MongoFieldNameSupport.toMongoFieldValue( rightFieldPath, lowInclusive ) ),
								binary( "$lte", "$" + MongoFieldNameSupport.toMongoField( rightFieldPath ), MongoFieldNameSupport.toMongoFieldValue( rightFieldPath, highInclusive ) )
							)
					)
				);
			return this;

		}

		public Builder bindConditionLike(
			String pattern, String rightFieldPath, Condition.LikeOperator options
		) {

			whereExprs.add( regexMatch( MongoFieldNameSupport.toMongoField( rightFieldPath ), pattern, options == null ? Condition.LikeOperator.i : options ) );
			return this;

		}

		public Builder bindConditionExists(
			String rightFieldPath, boolean exists
		) {

			whereExprs.add( existsExpr( MongoFieldNameSupport.toMongoField( rightFieldPath ), exists ) );
			return this;

		}

		public Builder bindConditionIsNull(
			String rightFieldPath
		) {

			whereExprs.add( binary( "$eq", "$" + MongoFieldNameSupport.toMongoField( rightFieldPath ), null ) );
			return this;

		}

		public Builder bindConditionIsNotNull(
			String rightFieldPath
		) {

			whereExprs.add( binary( "$ne", "$" + MongoFieldNameSupport.toMongoField( rightFieldPath ), null ) );
			return this;

		}

		/** Appends a raw MongoDB driver aggregation stage to the lookup pipeline. */
		public Builder rawStage(
			Bson stage
		) {

			if (stage != null) {
				pipeline.add( stage );

			}

			return this;

		}

		public Builder unwind(
			boolean preserveNullAndEmptyArrays
		) {

			spec.unwind = true;
			spec.preserveNullAndEmptyArrays = preserveNullAndEmptyArrays;
			return this;

		}

		public Builder limit(
			int n
		) {

			pipeline.add( Aggregates.limit( n ) );
			return this;

		}

		/** Starts ordered sorting for the lookup pipeline. */
		public SortSpec<Builder> sorts() {

			return new SortSpec<Builder>( this ) {

				@Override
				protected void apply() {

					if (! isEmpty()) {
						pipeline.add( Aggregates.sort( this ) );

					}

				}

			};

		}

		/** Configures ordered lookup sorting in one callback and returns this builder. */
		public Builder sorts(
			Consumer<SortSpec<Builder>> spec
		) {

			SortSpec<Builder> sort = sorts();
			Objects.requireNonNull( spec, "spec" ).accept( sort );
			return sort.end();

		}

		public LookupSpec build() {

			if (! whereExprs.isEmpty()) {
				pipeline
					.add(
						Aggregates
							.match(
								whereExprs.size() == 1
									? new Document( "$expr", whereExprs.get( 0 ) )
									: new Document( "$expr", new Document( "$and", List.copyOf( whereExprs ) ) )
							)
					);

			}

			spec.letDoc = new Document( letDoc );
			spec.pipelineDocs = List.copyOf( pipeline );
			spec.outerStages = List.copyOf( outerStages );
			return spec;

		}

		private void addConditionExpr(
			Condition cond, Object leftValueOrConst, String rightFieldPath
		) {

			String rightField = MongoFieldNameSupport.toMongoField( rightFieldPath );
			String right = "$" + rightField;

			switch (cond) {
				case eq -> whereExprs.add( binary( "$eq", right, leftValueOrConst ) );
				case notEq -> whereExprs.add( binary( "$ne", right, leftValueOrConst ) );
				case gt -> whereExprs.add( binary( "$gt", right, leftValueOrConst ) );
				case gte -> whereExprs.add( binary( "$gte", right, leftValueOrConst ) );
				case lt -> whereExprs.add( binary( "$lt", right, leftValueOrConst ) );
				case lte -> whereExprs.add( binary( "$lte", right, leftValueOrConst ) );
				case in -> {

					if (! (leftValueOrConst instanceof Collection<?> values)) { throw new IllegalArgumentException( "IN requires a collection constant" ); }

					whereExprs.add( new Document( "$in", List.of( right, values ) ) );

				}
				case notIn -> {

					if (! (leftValueOrConst instanceof Collection<?> values)) { throw new IllegalArgumentException( "NOT IN requires a collection constant" ); }

					whereExprs.add( new Document( "$not", new Document( "$in", List.of( right, values ) ) ) );

				}
				case like -> {

					if (! (leftValueOrConst instanceof String pattern)) { throw new IllegalArgumentException( "LIKE requires string pattern" ); }

					whereExprs.add( regexMatch( rightField, pattern, Condition.LikeOperator.i ) );

				}
				case regex -> {

					if (! (leftValueOrConst instanceof String pattern)) { throw new IllegalArgumentException( "REGEX requires pattern string" ); }

					whereExprs.add( regexMatch( rightField, pattern, null ) );

				}
				case exists -> {

					if (! (leftValueOrConst instanceof Boolean exists)) { throw new IllegalArgumentException( "EXISTS requires boolean" ); }

					whereExprs.add( existsExpr( MongoFieldNameSupport.toMongoField( rightFieldPath ), exists ) );

				}
				case isNull -> whereExprs.add( binary( "$eq", right, null ) );
				case isNotNull -> whereExprs.add( binary( "$ne", right, null ) );
				case all -> {

					if (! (leftValueOrConst instanceof Collection<?> values)) { throw new IllegalArgumentException( "ALL requires a collection" ); }

					whereExprs.add( new Document( "$setIsSubset", List.of( values, right ) ) );

				}
				case between -> {

					if (! (leftValueOrConst instanceof Collection<?> values) || values.size() != 2) { throw new IllegalArgumentException( "BETWEEN requires collection of size 2" ); }

					Object[] range = values.toArray();
					whereExprs
						.add(
							new Document(
								"$and",
								List.of( binary( "$gte", right, range[0] ), binary( "$lte", right, range[1] ) )
							)
						);

				}
				case near, nearSphere, elemMatch -> throw new UnsupportedOperationException(
					cond + " is not supported in lookup $expr builder; use rawStage(Bson)."
				);

			}

		}

		private static Document binary(
			String operator, Object left, Object right
		) {

			return new Document( operator, java.util.Arrays.asList( left, right ) );

		}

		private static Document regexMatch(
			String rightFieldPath, String pattern, Condition.LikeOperator options
		) {

			Document body = new Document( "input", "$" + rightFieldPath ).append( "regex", pattern );

			if (options != null) {
				body.append( "options", options.name() );

			}

			return new Document( "$regexMatch", body );

		}

		private static Document existsExpr(
			String rightFieldPath, boolean exists
		) {

			Document type = new Document( "$type", "$" + rightFieldPath );
			return binary( exists ? "$ne" : "$eq", type, "missing" );

		}

		private String nextVar() {

			return "v" + varSeq++;

		}

	}

}
