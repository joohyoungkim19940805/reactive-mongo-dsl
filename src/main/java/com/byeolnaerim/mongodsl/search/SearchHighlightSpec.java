package com.byeolnaerim.mongodsl.search;

import java.util.Arrays;
import java.util.Collection;
import org.bson.Document;

/**
 * Stage-level Atlas Search {@code highlight} specification.
 *
 * <p>This type models the {@code highlight} option that lives directly under
 * {@code $search}, not inside a specific operator such as {@code text} or
 * {@code autocomplete}. Use this through {@code SearchBuilder.highlight(...)}
 * and retrieve the rendered highlight result through
 * {@code $meta: "searchHighlights"}.</p>
 */
public final class SearchHighlightSpec {

	private final Object path;

	private final Integer maxCharsToExamine;

	private final Integer maxNumPassages;

	private SearchHighlightSpec(
		Object path,
		Integer maxCharsToExamine,
		Integer maxNumPassages
	) {

		this.path = path;
		this.maxCharsToExamine = maxCharsToExamine;
		this.maxNumPassages = maxNumPassages;

	}

	/**
	 * Creates a new builder for Atlas Search highlight options.
	 *
	 * @return a new highlight builder
	 */
	public static Builder builder() {

		return new Builder();

	}

	/**
	 * Renders this highlight specification into an Atlas Search document.
	 *
	 * @return the rendered highlight document
	 */
	public Document toDocument() {

		Document document = new Document( "path", this.path );

		if (this.maxCharsToExamine != null) {
			document.append( "maxCharsToExamine", this.maxCharsToExamine );

		}

		if (this.maxNumPassages != null) {
			document.append( "maxNumPassages", this.maxNumPassages );

		}

		return document;

	}

	/**
	 * Fluent builder for Atlas Search highlight options.
	 */
	public static final class Builder {

		private Object path;

		private Integer maxCharsToExamine;

		private Integer maxNumPassages;

		/**
		 * Sets a single highlight path.
		 *
		 * @param path
		 *            the logical path input
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> Builder path(
			K path
		) {

			this.path = SearchPathResolver.resolve( path );
			return this;

		}

		/**
		 * Sets multiple highlight paths.
		 *
		 * @param paths
		 *            the logical path inputs
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		public <K> Builder paths(
			Collection<K> paths
		) {

			this.path = SearchPathResolver.resolveAll( paths );
			return this;

		}

		/**
		 * Convenience overload for multiple highlight paths.
		 *
		 * @param paths
		 *            the logical path inputs
		 * @param <K>
		 *            the logical path type
		 *
		 * @return this builder
		 */
		@SafeVarargs
		public final <K> Builder paths(
			K... paths
		) {

			if (paths == null || paths.length == 0) {
				throw new IllegalArgumentException( "paths must not be empty" );

			}

			return paths( Arrays.asList( paths ) );

		}

		/**
		 * Sets the number of characters to examine per field.
		 *
		 * @param maxCharsToExamine
		 *            the maximum number of characters to examine
		 *
		 * @return this builder
		 */
		public Builder maxCharsToExamine(
			int maxCharsToExamine
		) {

			if (maxCharsToExamine <= 0) {
				throw new IllegalArgumentException( "maxCharsToExamine must be > 0" );

			}

			this.maxCharsToExamine = maxCharsToExamine;
			return this;

		}

		/**
		 * Sets the maximum number of highlight passages to return per field.
		 *
		 * @param maxNumPassages
		 *            the maximum number of passages
		 *
		 * @return this builder
		 */
		public Builder maxNumPassages(
			int maxNumPassages
		) {

			if (maxNumPassages <= 0) {
				throw new IllegalArgumentException( "maxNumPassages must be > 0" );

			}

			this.maxNumPassages = maxNumPassages;
			return this;

		}

		/**
		 * Builds the immutable highlight specification.
		 *
		 * @return the built highlight specification
		 */
		public SearchHighlightSpec build() {

			if (this.path == null) {
				throw new IllegalStateException( "highlight.path is required" );

			}

			return new SearchHighlightSpec(
				this.path,
				this.maxCharsToExamine,
				this.maxNumPassages
			);

		}

	}

}
