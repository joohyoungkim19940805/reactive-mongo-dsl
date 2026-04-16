package com.byeolnaerim.mongodsl.search;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bson.Document;

/**
 * Strongly typed Atlas Search score specification.
 *
 * <p>This type intentionally avoids a raw {@link Object}-centric public API and
 * instead exposes builder methods that describe the supported Atlas Search score
 * clauses in a DSL-friendly way.</p>
 */
public final class SearchScoreSpec {

	private final Document document;

	private SearchScoreSpec(
		Document document
	) {
		this.document = document;
	}

	/**
	 * Creates a score specification with a constant boost value.
	 *
	 * @param value
	 *            the boost value
	 *
	 * @return the score specification
	 */
	public static SearchScoreSpec boost(
		double value
	) {

		if (value <= 0d) {
			throw new IllegalArgumentException( "boost value must be > 0" );

		}

		return new SearchScoreSpec(
			new Document( "boost", new Document( "value", value ) )
		);

	}

	/**
	 * Creates a boost score whose source is another field path.
	 *
	 * @param path
	 *            the path to read the boost value from
	 * @param <K>
	 *            the logical path type
	 *
	 * @return the score specification
	 */
	public static <K> SearchScoreSpec boostByPath(
		K path
	) {
		return boostByPath( path, null );
	}

	/**
	 * Creates a boost score whose source is another field path and an optional
	 * fallback for missing values.
	 *
	 * @param path
	 *            the path to read the boost value from
	 * @param undefined
	 *            fallback value used when the source path is missing
	 * @param <K>
	 *            the logical path type
	 *
	 * @return the score specification
	 */
	public static <K> SearchScoreSpec boostByPath(
		K path,
		Double undefined
	) {

		Document boost = new Document( "path", SearchPathResolver.resolve( path ) );

		if (undefined != null) {
			boost.append( "undefined", undefined );

		}

		return new SearchScoreSpec( new Document( "boost", boost ) );

	}

	/**
	 * Creates a constant score specification.
	 *
	 * @param value
	 *            the constant score value
	 *
	 * @return the score specification
	 */
	public static SearchScoreSpec constant(
		double value
	) {
		return new SearchScoreSpec(
			new Document( "constant", new Document( "value", value ) )
		);
	}

	/**
	 * Creates a function-based score specification.
	 *
	 * @param spec
	 *            the function builder callback
	 *
	 * @return the score specification
	 */
	public static SearchScoreSpec function(
		Consumer<FunctionBuilder> spec
	) {
		FunctionBuilder builder = new FunctionBuilder();
		spec.accept( builder );
		return new SearchScoreSpec( new Document( "function", builder.build() ) );
	}

	/**
	 * Renders the score specification.
	 *
	 * @return the rendered score document
	 */
	public Document toDocument() {
		return new Document( this.document );
	}

	/**
	 * Builder for Atlas Search score function expressions.
	 */
	public static final class FunctionBuilder {

		private Document expression;

		private FunctionBuilder set(
			Document expression
		) {
			this.expression = expression;
			return this;
		}

		/**
		 * Uses a constant function value.
		 *
		 * @param value
		 *            the constant function value
		 *
		 * @return this builder
		 */
		public FunctionBuilder constant(
			double value
		) {
			return set( new Document( "constant", value ) );
		}

		/**
		 * Uses the current search relevance score.
		 *
		 * @return this builder
		 */
		public FunctionBuilder scoreRelevance() {
			return set( new Document( "score", "relevance" ) );
		}

		/**
		 * Uses a numeric field path as the score function input.
		 *
		 * @param path
		 *            the input path
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> FunctionBuilder path(
			K path
		) {
			return set( new Document( "path", SearchPathResolver.resolve( path ) ) );
		}

		/**
		 * Uses a numeric field path as the score function input with a fallback value
		 * for missing data.
		 *
		 * @param path
		 *            the input path
		 * @param undefined
		 *            fallback value for missing data
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> FunctionBuilder path(
			K path,
			double undefined
		) {
			return set(
				new Document(
					"path",
					new Document()
						.append( "value", SearchPathResolver.resolve( path ) )
						.append( "undefined", undefined )
				)
			);
		}

		/**
		 * Builds an {@code add} function expression.
		 *
		 * @param spec
		 *            the nested expression builder callback
		 *
		 * @return this builder
		 */
		public FunctionBuilder add(
			Consumer<ExpressionArrayBuilder> spec
		) {
			ExpressionArrayBuilder builder = new ExpressionArrayBuilder();
			spec.accept( builder );
			return set( new Document( "add", builder.build() ) );
		}

		/**
		 * Builds a {@code multiply} function expression.
		 *
		 * @param spec
		 *            the nested expression builder callback
		 *
		 * @return this builder
		 */
		public FunctionBuilder multiply(
			Consumer<ExpressionArrayBuilder> spec
		) {
			ExpressionArrayBuilder builder = new ExpressionArrayBuilder();
			spec.accept( builder );
			return set( new Document( "multiply", builder.build() ) );
		}

		/**
		 * Builds a {@code gauss} function expression.
		 *
		 * @param path
		 *            the source path
		 * @param origin
		 *            the origin value
		 * @param scale
		 *            the scale value
		 * @param offset
		 *            the offset value
		 * @param decay
		 *            the decay value
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> FunctionBuilder gauss(
			K path,
			double origin,
			double scale,
			double offset,
			double decay
		) {
			return gauss( path, null, origin, scale, offset, decay );
		}

		/**
		 * Builds a {@code gauss} function expression with a fallback value for missing
		 * path data.
		 *
		 * @param path
		 *            the source path
		 * @param undefined
		 *            fallback value for missing path data
		 * @param origin
		 *            the origin value
		 * @param scale
		 *            the scale value
		 * @param offset
		 *            the offset value
		 * @param decay
		 *            the decay value
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> FunctionBuilder gauss(
			K path,
			Double undefined,
			double origin,
			double scale,
			double offset,
			double decay
		) {

			Document pathDocument = new Document( "value", SearchPathResolver.resolve( path ) );

			if (undefined != null) {
				pathDocument.append( "undefined", undefined );

			}

			return set(
				new Document(
					"gauss",
					new Document()
						.append( "path", pathDocument )
						.append( "origin", origin )
						.append( "scale", scale )
						.append( "offset", offset )
						.append( "decay", decay )
				)
			);
		}

		/**
		 * Builds a {@code log} function expression around another function.
		 *
		 * @param nested
		 *            the nested function builder callback
		 *
		 * @return this builder
		 */
		public FunctionBuilder log(
			Consumer<FunctionBuilder> nested
		) {
			FunctionBuilder builder = new FunctionBuilder();
			nested.accept( builder );
			return set( new Document( "log", builder.build() ) );
		}

		/**
		 * Builds a {@code log1p} function expression around another function.
		 *
		 * @param nested
		 *            the nested function builder callback
		 *
		 * @return this builder
		 */
		public FunctionBuilder log1p(
			Consumer<FunctionBuilder> nested
		) {
			FunctionBuilder builder = new FunctionBuilder();
			nested.accept( builder );
			return set( new Document( "log1p", builder.build() ) );
		}

		Document build() {
			if (this.expression == null || this.expression.isEmpty()) {
				throw new IllegalStateException( "function score expression is required" );

			}

			return new Document( this.expression );
		}
	}

	/**
	 * Builder for {@code add} and {@code multiply} function argument arrays.
	 */
	public static final class ExpressionArrayBuilder {

		private final List<Document> expressions = new ArrayList<>();

		/**
		 * Adds a constant expression.
		 *
		 * @param value
		 *            the constant value
		 *
		 * @return this builder
		 */
		public ExpressionArrayBuilder constant(
			double value
		) {
			this.expressions.add( new Document( "constant", value ) );
			return this;
		}

		/**
		 * Adds the current search relevance score expression.
		 *
		 * @return this builder
		 */
		public ExpressionArrayBuilder scoreRelevance() {
			this.expressions.add( new Document( "score", "relevance" ) );
			return this;
		}

		/**
		 * Adds a path expression.
		 *
		 * @param path
		 *            the path input
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> ExpressionArrayBuilder path(
			K path
		) {
			this.expressions.add( new Document( "path", SearchPathResolver.resolve( path ) ) );
			return this;
		}

		/**
		 * Adds a path expression with a fallback value for missing data.
		 *
		 * @param path
		 *            the path input
		 * @param undefined
		 *            fallback value for missing data
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> ExpressionArrayBuilder path(
			K path,
			double undefined
		) {
			this.expressions.add(
				new Document(
					"path",
					new Document()
						.append( "value", SearchPathResolver.resolve( path ) )
						.append( "undefined", undefined )
				)
			);
			return this;
		}

		/**
		 * Adds a nested function expression.
		 *
		 * @param spec
		 *            the nested function builder callback
		 *
		 * @return this builder
		 */
		public ExpressionArrayBuilder expression(
			Consumer<FunctionBuilder> spec
		) {
			FunctionBuilder builder = new FunctionBuilder();
			spec.accept( builder );
			this.expressions.add( builder.build() );
			return this;
		}

		List<Document> build() {
			if (this.expressions.size() < 2) {
				throw new IllegalStateException( "add/multiply expressions must have at least 2 elements" );

			}

			return new ArrayList<>( this.expressions );
		}
	}
}
