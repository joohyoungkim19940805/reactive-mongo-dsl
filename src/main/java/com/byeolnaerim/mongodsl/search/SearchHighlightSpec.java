package com.byeolnaerim.mongodsl.search;


import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.bson.Document;
import com.byeolnaerim.mongodsl.internal.MongoBsonSupport;
import com.mongodb.client.model.search.SearchHighlight;
import com.mongodb.client.model.search.SearchPath;


/**
 * Stage-level Atlas Search highlight specification backed by MongoDB driver's
 * {@link SearchHighlight}.
 */
public final class SearchHighlightSpec {

	private final SearchHighlight highlight;

	private SearchHighlightSpec(
								SearchHighlight highlight
	) {

		this.highlight = highlight;

	}

	/**
	 * Creates a new builder for Atlas Search highlight options.
	 *
	 * @return a new highlight builder
	 */
	public static Builder builder() {

		return new Builder();

	}

	public SearchHighlight toSearchHighlight() {

		return this.highlight;

	}

	/**
	 * Renders this highlight specification into an Atlas Search document.
	 *
	 * @return the rendered highlight document
	 */
	public Document toDocument() {

		return MongoBsonSupport.toDocument( this.highlight );

	}

	public static final class Builder {

		private List<SearchPath> paths;

		private Integer maxCharsToExamine;

		private Integer maxNumPassages;

		public Builder path(
			String path
		) {

			this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
			return this;

		}

		public Builder path(
			Enum<?> path
		) {

			this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
			return this;

		}

		public Builder path(
			SearchPath path
		) {

			this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
			return this;

		}

		/** Fallback for custom path wrappers. */
		public Builder path(
			Object path
		) {

			this.paths = List.of( SearchPathResolver.resolveSearchPath( path ) );
			return this;

		}

		public Builder paths(
			String path, String... paths
		) {

			return paths(
				java.util.stream.Stream
					.concat(
						java.util.stream.Stream.of( path ),
						Arrays.stream( paths )
					)
					.toList()
			);

		}

		public Builder paths(
			Enum<?> path, Enum<?>... paths
		) {

			return paths(
				java.util.stream.Stream
					.concat(
						java.util.stream.Stream.of( path ),
						Arrays.stream( paths )
					)
					.toList()
			);

		}

		public Builder paths(
			SearchPath path, SearchPath... paths
		) {

			return paths(
				java.util.stream.Stream
					.concat(
						java.util.stream.Stream.of( path ),
						Arrays.stream( paths )
					)
					.toList()
			);

		}

		/** Fallback for mixed/custom path wrappers. */
		public Builder paths(
			Object path, Object... paths
		) {

			return paths(
				java.util.stream.Stream
					.concat(
						java.util.stream.Stream.of( path ),
						Arrays.stream( paths )
					)
					.toList()
			);

		}

		public Builder paths(
			Collection<?> paths
		) {

			this.paths = SearchPathResolver.resolveSearchPaths( paths );
			return this;

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

			if (maxCharsToExamine <= 0) { throw new IllegalArgumentException( "maxCharsToExamine must be > 0" ); }

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

			if (maxNumPassages <= 0) { throw new IllegalArgumentException( "maxNumPassages must be > 0" ); }

			this.maxNumPassages = maxNumPassages;
			return this;

		}

		/**
		 * Builds the immutable highlight specification.
		 *
		 * @return the built highlight specification
		 */
		public SearchHighlightSpec build() {

			if (this.paths == null || this.paths.isEmpty()) { throw new IllegalStateException( "highlight.path is required" ); }

			SearchHighlight highlight = SearchHighlight.paths( this.paths );

			if (this.maxCharsToExamine != null) {
				highlight = highlight.maxCharsToExamine( this.maxCharsToExamine );

			}

			if (this.maxNumPassages != null) {
				highlight = highlight.maxNumPassages( this.maxNumPassages );

			}

			return new SearchHighlightSpec( highlight );

		}

	}

}
