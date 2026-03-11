package com.byeolnaerim.mongodsl.lookup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import com.byeolnaerim.mongodsl.criteria.FieldsPair;
import com.byeolnaerim.mongodsl.criteria.FieldsPair.Condition;

/**
 * Encapsulates lookup stage options such as {@code as}, join key mapping via
 * {@code localField/foreignField} or {@code let + pipeline}, optional {@code $unwind},
 * and post-lookup outer stages.
 *
 * <p>Usage examples:</p>
 *
 * <pre>{@code
 * // 현재 계정 보유 여부 조인 (title ↔ accountTitle)
 * var spec = LookupSpec
 *   .builder()
 *   .as("ownHit")
 *   // title._id == accountTitle.titleId
 *   .bindConditionFields("_id", Condition.eq, "titleId")
 *   // accountTitle.accountId == accountId
 *   .bindConditionConst(accountId, Condition.eq, "accountId")
 *   .limit(1)
 *   // .unwind(true) // 단건화 원하면
 *   .build();
 *
 * // 이름 부분 일치 (대소문자 무시)
 * var spec2 = LookupSpec
 *   .builder()
 *   .as("matched")
 *   .bindConditionLike(".*pro.*", "name", Condition.LikeOperator.i)
 *   .build();
 *
 * // 카테고리 in (...)
 * var spec3 = LookupSpec
 *   .builder()
 *   .as("matched")
 *   .bindConditionConst(List.of("rpg", "indie"), Condition.in, "category")
 *   .build();
 *
 * // createdAt between
 * var spec4 = LookupSpec
 *   .builder()
 *   .as("ownHit")
 *   .bindConditionBetween(startInstant, endInstant, "createdAt")
 *   .build();
 * }</pre>
 *
 * @see LookupSpec.Builder
 * @see LookupSpec.Builder#as(String)
 * @see LookupSpec.Builder#localField(String)
 * @see LookupSpec.Builder#foreignField(String)
 * @see LookupSpec.Builder#bindConditionFields(String, Condition, String)
 * @see LookupSpec.Builder#bindConditionConst(Object, Condition, String)
 * @see LookupSpec.Builder#bindConditionBetween(Object, Object, String)
 * @see LookupSpec.Builder#bindConditionLike(String, String, Condition.LikeOperator)
 * @see LookupSpec.Builder#bindConditionExists(String, boolean)
 * @see LookupSpec.Builder#unwind(boolean)
 * @see LookupSpec.Builder#outerStage(org.bson.Document)
 * @see LookupSpec.Builder#outerStages(java.util.Collection)
 * @see LookupSpec.Builder#build()
 */
public class LookupSpec {

	// // 최종 결과물 (executeLookup에서 사용)
	// private String from;
	private List<Document> outerStages = new ArrayList<>();

	private String as;

	private String localField;

	private String foreignField;

	private Document letDoc; // 빌더가 조립

	private List<Document> pipelineDocs; // 빌더가 조립

	private boolean unwind;

	private boolean preserveNullAndEmptyArrays;

	private LookupSpec() {}

	// 게터 (executeLookup에서 접근)
	// public String getFrom() { return from; }

	/**
	 * Returns post-lookup aggregation stages that are applied after {@code $lookup}
	 * and optional {@code $unwind}.
	 *
	 * @return post-lookup outer stages
	 */
	public List<Document> getOuterStages() { return outerStages; }

	/**
	 * Returns the alias used for the lookup result.
	 *
	 * @return the lookup alias
	 */
	public String getAs() { return as; }

	/**
	 * Returns the local join field used in simple lookup mode.
	 *
	 * @return the local field name
	 */
	public String getLocalField() { return localField; }

	/**
	 * Returns the foreign join field used in simple lookup mode.
	 *
	 * @return the foreign field name
	 */
	public String getForeignField() { return foreignField; }

	/**
	 * Returns the {@code let} document used in pipeline-based lookup mode.
	 *
	 * @return the lookup {@code let} document
	 */
	public Document getLetDoc() { return letDoc; }

	/**
	 * Returns the lookup pipeline stages used in pipeline-based lookup mode.
	 *
	 * @return the lookup pipeline stages
	 */
	public List<Document> getPipelineDocs() { return pipelineDocs; }

	/**
	 * Returns whether the lookup result should be unwound.
	 *
	 * @return {@code true} if {@code $unwind} is enabled
	 */
	public boolean isUnwind() { return unwind; }

	/**
	 * Returns whether {@code $unwind} should preserve null and empty arrays.
	 *
	 * @return {@code true} if null and empty arrays should be preserved
	 */
	public boolean isPreserveNullAndEmptyArrays() { return preserveNullAndEmptyArrays; }

	/**
	 * Creates a new {@link Builder}.
	 *
	 * @return a new lookup specification builder
	 */
	public static Builder builder() {

		return new Builder();

	}

	/**
	 * Fluent builder for constructing {@link LookupSpec} instances.
	 * <p>This builder supports simple lookup mode using {@code localField}/{@code foreignField}
	 * as well as advanced lookup mode using {@code let + pipeline + $expr} conditions.</p>
	 */
	public static class Builder {

		private List<Document> outerStages = new ArrayList<>();

		private final LookupSpec spec = new LookupSpec();

		private final Document letDoc = new Document();

		private final List<Document> pipeline = new ArrayList<>();

		private final List<Document> whereExprs = new ArrayList<>();

		private int varSeq = 0;

		/**
		 * Adds a post-lookup aggregation stage to be applied after {@code $lookup}
		 * and optional {@code $unwind}.
		 *
		 * @param stage
		 *            the outer stage to append
		 * 
		 * @return this builder
		 */
		public Builder outerStage(
			Document stage
		) {

			if (stage != null)
				this.outerStages.add( stage );
			return this;

		}

		/**
		 * Adds multiple post-lookup aggregation stages.
		 *
		 * @param stages
		 *            the outer stages to append
		 * 
		 * @return this builder
		 */
		public Builder outerStages(
			Collection<Document> stages
		) {

			if (stages != null) {
				for (Document s : stages)
					if (s != null)
						this.outerStages.add( s );

			}

			return this;

		}

		/**
		 * Adds a post-lookup {@code $match} stage that wraps the given expression in {@code $expr}.
		 *
		 * @param expr
		 *            the expression to wrap in {@code $expr}
		 * 
		 * @return this builder
		 */
		public Builder outerMatchExpr(
			Document expr
		) {

			if (expr != null)
				this.outerStages.add( new Document( "$match", new Document( "$expr", expr ) ) );
			return this;

		}

		private Document exprBinary(
			String op, Object left, Object right
		) {

			return new Document( op, List.of( left, right ) );

		}

		private Document exprAnd(
			List<Document> parts
		) {

			if (parts.size() == 1)
				return new Document( "$expr", parts.get( 0 ) );
			return new Document( "$expr", new Document( "$and", parts ) );

		}

		/** NOT {$in: [...] } */
		private Document exprNotIn(
			Object needle, Collection<?> haystack
		) {

			return new Document(
				"$not",
				new Document( "$in", List.of( needle, haystack ) )
			);

		}

		/** field exists in $expr 방식: type != "missing" */
		private Document exprExists(
			String rightFieldPath, boolean exists
		) {

			Document type = new Document( "$type", "$" + rightFieldPath );

			if (exists) {
				return exprBinary( "$gt", type, "missing" ); // "$type" > "missing"

			} else {
				return exprBinary( "$eq", type, "missing" );

			}

		}

		/** like/regex: $regexMatch 사용 */
		private Document exprRegexMatch(
			String rightFieldPath, String pattern, Condition.LikeOperator options
		) {

			Document body = new Document( "input", "$" + rightFieldPath )
				.append( "regex", pattern );
			if (options != null)
				body.append( "options", options.name() );
			return new Document( "$regexMatch", body );

		}

		/** all: const ⊆ field (둘 다 배열) → $setIsSubset */
		private Document exprAll(
			Collection<?> constArray, String rightFieldPath
		) {

			return new Document(
				"$setIsSubset",
				List.of( constArray, "$" + rightFieldPath )
			);

		}

		/** between: low <= field <= high */
		private Document exprBetween(
			String rightFieldPath, Object low, Object high
		) {

			Document gte = exprBinary( "$gte", "$" + rightFieldPath, low );
			Document lte = exprBinary( "$lte", "$" + rightFieldPath, high );
			return new Document( "$and", List.of( gte, lte ) );

		}

		// --- 기본 메타 ---
		// public Builder from(
		// String from
		// ) {
		//
		// spec.from = from;
		// return this;
		//
		// }

		/**
		 * Sets the alias of the lookup result.
		 *
		 * @param as the lookup alias
		 * @return this builder
		 */
		public Builder as(
			String as
		) {

			spec.as = as;
			return this;

		}

		/**
		 * Sets the local join field for simple lookup mode.
		 *
		 * @param localField the local field name
		 * @return this builder
		 */
		public Builder localField(
			String localField
		) {

			spec.localField = localField;
			return this;

		}

		/**
		 * Sets the foreign join field for simple lookup mode.
		 *
		 * @param foreignField the foreign field name
		 * @return this builder
		 */
		public Builder foreignField(
			String foreignField
		) {

			spec.foreignField = foreignField;
			return this;

		}

		/**
		 * Binds a condition between a left-side field and a right-side field using {@code $expr}.
		 * 왼쪽(현재 컬렉션)의 leftFieldPath 와 오른쪽 rightFieldPath 사이에 Condition 적용
		 *
		 * @param leftFieldPath
		 *            the field path in the left collection
		 * @param cond
		 *            the comparison condition
		 * @param rightFieldPath
		 *            the field path in the right collection
		 * 
		 * @return this builder
		 */
		public Builder bindConditionFields(
			String leftFieldPath, Condition cond, String rightFieldPath
		) {

			String var = nextVar();
			letDoc.put( var, "$" + leftFieldPath ); // $$var = "$leftFieldPath"
			addConditionExpr( cond, "$$" + var, rightFieldPath, null, null, null );
			return this;

		}

		/**
		 * Binds a condition between a constant value and a right-side field using {@code $expr}.
		 * 상수 constValue 와 오른쪽 rightFieldPath 사이에 Condition 적용
		 *
		 * @param constValue
		 *            the constant value
		 * @param cond
		 *            the comparison condition
		 * @param rightFieldPath
		 *            the field path in the right collection
		 * 
		 * @return this builder
		 */
		public Builder bindConditionConst(
			Object constValue, Condition cond, String rightFieldPath
		) {

			addConditionExpr( cond, constValue, rightFieldPath, null, null, null );
			return this;

		}

		/**
		 * Converts the given left-side string field to {@code ObjectId}
		 * and compares it with the specified right-side field.
		 * <p>Conversion failures are treated as {@code null}, which results in no match
		 * instead of a query failure.</p>
		 * * 왼쪽 필드(String ObjectId hex)를 ObjectId로 변환해서 오른쪽 필드(ObjectId)와 비교하도록 바인딩
		 * - 예: left.auctionId(String) == right._id(ObjectId)
		 * - $convert 사용: 변환 실패 시 null로 처리되어 쿼리 에러 없이 매칭 0건 처리됨
		 * 
		 * @param leftFieldPath
		 *            the left-side field path containing an ObjectId hex string
		 * @param cond
		 *            the comparison condition
		 * @param rightFieldPath
		 *            the right-side field path
		 * 
		 * @return this builder
		 */
		public Builder bindConditionFieldsLeftToObjectId(
			String leftFieldPath, Condition cond, String rightFieldPath
		) {

			String var = nextVar();

			// $$var = {$convert: {input:"$auctionId", to:"objectId", onError:null, onNull:null}}
			Document toObjectIdExpr = new Document(
				"$convert",
				new Document( "input", "$" + leftFieldPath )
					.append( "to", "objectId" )
					.append( "onError", null )
					.append( "onNull", null )
			);

			letDoc.put( var, toObjectIdExpr );

			// 기존 addConditionExpr 로직 재사용: $eq: ["$_id", "$$v0"] 형태로 들어감
			addConditionExpr( cond, "$$" + var, rightFieldPath, null, null, null );
			return this;

		}

		/**
		 * Adds an inclusive range condition for the given right-side field.
		 * between(low, high) 상수 범위
		 *
		 * @param lowInclusive
		 *            the lower bound
		 * @param highInclusive
		 *            the upper bound
		 * @param rightFieldPath
		 *            the target field path in the right collection
		 * 
		 * @return this builder
		 */
		public Builder bindConditionBetween(
			Object lowInclusive, Object highInclusive, String rightFieldPath
		) {

			whereExprs.add( exprBetween( rightFieldPath, lowInclusive, highInclusive ) );
			return this;

		}

		/**
		 * Adds a regex-based match condition for the given right-side field.
		 * like/regex 전용 옵션 (기본 i-case-insensitive)
		 * 
		 * @param pattern
		 *            the regex pattern
		 * @param rightFieldPath
		 *            the target field path in the right collection
		 * @param options
		 *            the regex options; when {@code null}, case-insensitive matching is used
		 * 
		 * @return this builder
		 */
		public Builder bindConditionLike(
			String pattern, String rightFieldPath, Condition.LikeOperator options /* nullable */
		) {

			whereExprs.add( exprRegexMatch( rightFieldPath, pattern, options == null ? Condition.LikeOperator.i : options ) );
			return this;

		}

		/**
		 * Adds a field-existence condition for the given right-side field.
		 * exists / isNull / isNotNull 전용
		 * 
		 * @param rightFieldPath
		 *            the target field path in the right collection
		 * @param exists
		 *            whether the field should exist
		 * 
		 * @return this builder
		 */
		public Builder bindConditionExists(
			String rightFieldPath, boolean exists
		) {

			whereExprs.add( exprExists( rightFieldPath, exists ) );
			return this;

		}

		/**
		 * Adds a null-check condition for the given right-side field.
		 *
		 * @param rightFieldPath
		 *            the target field path in the right collection
		 * 
		 * @return this builder
		 */
		public Builder bindConditionIsNull(
			String rightFieldPath
		) {

			whereExprs.add( exprBinary( "$eq", "$" + rightFieldPath, null ) );
			return this;

		}

		/**
		 * Adds a non-null condition for the given right-side field.
		 *
		 * @param rightFieldPath
		 *            the target field path in the right collection
		 * 
		 * @return this builder
		 */
		public Builder bindConditionIsNotNull(
			String rightFieldPath
		) {

			whereExprs.add( exprBinary( "$ne", "$" + rightFieldPath, null ) );
			return this;

		}

		/**
		 * Appends a raw pipeline stage to the lookup pipeline.
		 * raw $match stage 추가(옵션)
		 *
		 * @param stage
		 *            the raw stage document
		 * 
		 * @return this builder
		 */
		public Builder rawStage(
			Document stage
		) {

			pipeline.add( stage );
			return this;

		}

		/**
		 * Enables {@code $unwind} for the lookup result.
		 *
		 * @param preserveNullAndEmptyArrays
		 *            whether null and empty arrays should be preserved
		 *            - false → INNER JOIN처럼 동작 (매칭 없으면 row 제거)
		 *            - true → LEFT OUTER JOIN처럼 동작 (매칭 없으면 null row 유지)
		 * 
		 * @return this builder
		 */
		public Builder unwind(
			boolean preserveNullAndEmptyArrays
		) {

			spec.unwind = true;
			spec.preserveNullAndEmptyArrays = preserveNullAndEmptyArrays;
			return this;

		}

		/**
		 * Appends a {@code $limit} stage to the lookup pipeline.
		 *
		 * @param n
		 *            the maximum number of joined documents to keep
		 * 
		 * @return this builder
		 */
		public Builder limit(
			int n
		) {

			pipeline.add( new Document( "$limit", n ) );
			return this;

		}

		/**
		 * Appends a {@code $sort} stage to the lookup pipeline.
		 *
		 * @param sort
		 *            the sort definition
		 * 
		 * @return this builder
		 */
		public Builder sort(
			Sort sort
		) {

			if (sort == null || sort.isUnsorted())
				return this;
			Document sortDoc = new Document();
			sort.forEach( o -> sortDoc.append( o.getProperty(), o.isAscending() ? 1 : -1 ) );
			pipeline.add( new Document( "$sort", sortDoc ) );
			return this;

		}

		/**
		 * Builds a {@link LookupSpec} from the configured join mapping, conditions,
		 * pipeline stages, and outer stages.
		 *
		 * @return the built lookup specification
		 */
		public LookupSpec build() {

			if (spec.localField == null || spec.foreignField == null) {

				// Condition 기반으로 쌓인 expr들을 하나의 $expr $match로 바꿔 삽입
				if (! whereExprs.isEmpty()) {
					pipeline.add( new Document( "$match", exprAnd( whereExprs ) ) );

				}

				spec.letDoc = letDoc;
				spec.pipelineDocs = pipeline;

			} else {
				spec.letDoc = new Document();
				spec.pipelineDocs = List.of();

			}

			spec.outerStages = this.outerStages;
			return spec;

		}

		private String nextVar() {

			return "v" + (varSeq++);

		}

		/** Condition → $expr 생성기 (왼쪽 값은 leftVal, 오른쪽은 rightFieldPath) */
		private void addConditionExpr(
			FieldsPair.Condition cond, Object leftValOrConst, // "$$var" 또는 상수
			String rightFieldPath, Collection<?> collectionOrNull, Object lowInclusiveOrNull, Object highInclusiveOrNull
		) {

			String rightFieldRef = "$" + rightFieldPath;

			switch (cond) {
				case eq -> whereExprs.add( exprBinary( "$eq", rightFieldRef, leftValOrConst ) );
				case notEq -> whereExprs.add( exprBinary( "$ne", rightFieldRef, leftValOrConst ) );

				case gt -> whereExprs.add( exprBinary( "$gt", rightFieldRef, leftValOrConst ) );
				case gte -> whereExprs.add( exprBinary( "$gte", rightFieldRef, leftValOrConst ) );
				case lt -> whereExprs.add( exprBinary( "$lt", rightFieldRef, leftValOrConst ) );
				case lte -> whereExprs.add( exprBinary( "$lte", rightFieldRef, leftValOrConst ) );

				case in -> {

					if (leftValOrConst instanceof Collection<?> col) {
						whereExprs.add( new Document( "$in", List.of( rightFieldRef, col ) ) );

					} else {
						throw new IllegalArgumentException( "IN requires a collection constant" );

					}

				}
				case notIn -> {

					if (leftValOrConst instanceof Collection<?> col) {
						whereExprs.add( exprNotIn( rightFieldRef, col ) );

					} else {
						throw new IllegalArgumentException( "NOT IN requires a collection constant" );

					}

				}

				case like -> {

					if (! (leftValOrConst instanceof String pat)) { throw new IllegalArgumentException( "LIKE requires string pattern" ); }

					// 기본 options: "i" (대소문자 무시)
					whereExprs.add( exprRegexMatch( rightFieldPath, pat, Condition.LikeOperator.i ) );

				}
				case regex -> {

					if (! (leftValOrConst instanceof String pat)) { throw new IllegalArgumentException( "REGEX requires pattern string" ); }

					// 옵션은 필요하면 bindConditionLike(...)로
					whereExprs.add( exprRegexMatch( rightFieldPath, pat, null ) );

				}

				case exists -> {

					if (! (leftValOrConst instanceof Boolean b)) { throw new IllegalArgumentException( "EXISTS requires boolean" ); }

					whereExprs.add( exprExists( rightFieldPath, b ) );

				}
				case isNull -> whereExprs.add( exprBinary( "$eq", rightFieldRef, null ) );
				case isNotNull -> whereExprs.add( exprBinary( "$ne", rightFieldRef, null ) );

				case all -> {

					if (! (leftValOrConst instanceof Collection<?> col)) { throw new IllegalArgumentException( "ALL requires a collection" ); }

					whereExprs.add( exprAll( col, rightFieldPath ) );

				}

				case between -> {

					if (leftValOrConst instanceof Collection<?> values && values.size() == 2) {
						Object[] arr = values.toArray();
						whereExprs.add( exprBetween( rightFieldPath, arr[0], arr[1] ) );

					} else {
						throw new IllegalArgumentException( "BETWEEN requires collection of size 2" );

					}

				}

				// `$lookup` 파이프라인의 $expr에서 직접 다루지 않는/지원 안 하는 항목
				case near, nearSphere, elemMatch -> throw new UnsupportedOperationException(
					cond + " is not supported in lookup $expr builder; use dedicated geo/array stages."
				);

				default -> throw new IllegalArgumentException( "Unsupported condition: " + cond );

			}

		}

	}

}