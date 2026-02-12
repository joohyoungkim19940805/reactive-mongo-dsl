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

	public List<Document> getOuterStages() { return outerStages; }

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

	public String getAs() { return as; }

	public String getLocalField() { return localField; }

	public String getForeignField() { return foreignField; }

	public Document getLetDoc() { return letDoc; }

	public List<Document> getPipelineDocs() { return pipelineDocs; }

	public boolean isUnwind() { return unwind; }

	public boolean isPreserveNullAndEmptyArrays() { return preserveNullAndEmptyArrays; }

	public static Builder builder() {

		return new Builder();

	}

	/**
	 * Fluent builder for constructing {@link LookupSpec} instances.
	 * <p>Provides chainable methods to define <code>from</code>, <code>as</code>, conditions,
	 * pipeline stages, and finally {@link #build()}.</p>
	 * <p>For detailed usage examples, see the Javadoc on {@link LookupSpec}.</p>
	 */
	public static class Builder {

		private List<Document> outerStages = new ArrayList<>(); // ← 추가

		private final LookupSpec spec = new LookupSpec();

		private final Document letDoc = new Document();

		private final List<Document> pipeline = new ArrayList<>();

		private final List<Document> whereExprs = new ArrayList<>();

		private int varSeq = 0;

		/** $lookup(+optional $unwind) 이후에 적용될 스테이지 */
		public Builder outerStage(
			Document stage
		) {

			if (stage != null)
				this.outerStages.add( stage );
			return this;

		}

		/** 여러 외부 스테이지를 한 번에 추가합니다. */
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

		public Builder as(
			String as
		) {

			spec.as = as;
			return this;

		}

		// --- 간단 모드(local/foreign) 그대로 지원 ---
		public Builder localField(
			String localField
		) {

			spec.localField = localField;
			return this;

		}

		public Builder foreignField(
			String foreignField
		) {

			spec.foreignField = foreignField;
			return this;

		}

		/** 왼쪽(현재 컬렉션)의 leftFieldPath 와 오른쪽 rightFieldPath 사이에 Condition 적용 */
		public Builder bindConditionFields(
			String leftFieldPath, Condition cond, String rightFieldPath
		) {

			String var = nextVar();
			letDoc.put( var, "$" + leftFieldPath ); // $$var = "$leftFieldPath"
			addConditionExpr( cond, "$$" + var, rightFieldPath, null, null, null );
			return this;

		}

		/** 상수 constValue 와 오른쪽 rightFieldPath 사이에 Condition 적용 */
		public Builder bindConditionConst(
			Object constValue, Condition cond, String rightFieldPath
		) {

			addConditionExpr( cond, constValue, rightFieldPath, null, null, null );
			return this;

		}

		/**
		 * 왼쪽 필드(String ObjectId hex)를 ObjectId로 변환해서 오른쪽 필드(ObjectId)와 비교하도록 바인딩
		 * - 예: left.auctionId(String) == right._id(ObjectId)
		 * - $convert 사용: 변환 실패 시 null로 처리되어 쿼리 에러 없이 매칭 0건 처리됨
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

		/** between(low, high) 상수 범위 */
		public Builder bindConditionBetween(
			Object lowInclusive, Object highInclusive, String rightFieldPath
		) {

			whereExprs.add( exprBetween( rightFieldPath, lowInclusive, highInclusive ) );
			return this;

		}

		/** like/regex 전용 옵션 (기본 i-case-insensitive) */
		public Builder bindConditionLike(
			String pattern, String rightFieldPath, Condition.LikeOperator options /* nullable */
		) {

			whereExprs.add( exprRegexMatch( rightFieldPath, pattern, options == null ? Condition.LikeOperator.i : options ) );
			return this;

		}

		/** exists / isNull / isNotNull 전용 */
		public Builder bindConditionExists(
			String rightFieldPath, boolean exists
		) {

			whereExprs.add( exprExists( rightFieldPath, exists ) );
			return this;

		}

		public Builder bindConditionIsNull(
			String rightFieldPath
		) {

			whereExprs.add( exprBinary( "$eq", "$" + rightFieldPath, null ) );
			return this;

		}

		public Builder bindConditionIsNotNull(
			String rightFieldPath
		) {

			whereExprs.add( exprBinary( "$ne", "$" + rightFieldPath, null ) );
			return this;

		}

		/** raw $match stage 추가(옵션) */
		public Builder rawStage(
			Document stage
		) {

			pipeline.add( stage );
			return this;

		}

		/**
		 * $unwind 설정
		 *
		 * @param preserveNullAndEmptyArrays
		 *            - false → INNER JOIN처럼 동작 (매칭 없으면 row 제거)
		 *            - true → LEFT OUTER JOIN처럼 동작 (매칭 없으면 null row 유지)
		 * 
		 * @return this
		 */
		public Builder unwind(
			boolean preserveNullAndEmptyArrays
		) {

			spec.unwind = true;
			spec.preserveNullAndEmptyArrays = preserveNullAndEmptyArrays;
			return this;

		}

		/** 파이프라인 보조 */
		public Builder limit(
			int n
		) {

			pipeline.add( new Document( "$limit", n ) );
			return this;

		}

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