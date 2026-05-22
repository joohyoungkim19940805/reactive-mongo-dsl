package com.byeolnaerim.mongodsl.vector;

/**
 * Logical embedding input type used by vector embedding providers.
 *
 * <p>The DSL does not own any HTTP client or provider-specific dependency.
 * Applications can map this value to provider-specific options such as Voyage
 * {@code input_type=query/document}, OpenAI-compatible request metadata, or an
 * internal embedding gateway contract.</p>
 */
public enum VectorEmbeddingInputType {
	/** Search query text. */
	QUERY,
	/** Corpus document or chunk text. */
	DOCUMENT
}
