package com.byeolnaerim.mongodsl.vector;

import java.util.Collection;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provider-neutral vector embedding function.
 *
 * <p>This interface intentionally has no HTTP-client dependency. A consuming
 * application may implement it with Spring {@code WebClient}, Reactor Netty,
 * an SDK, a gateway service, or any other client. The DSL only requires the
 * final {@link VectorQueryVector} wrapped in a {@link Mono}.</p>
 */
@FunctionalInterface
public interface VectorEmbeddingFunction {

	/**
	 * Embeds the given text for the specified logical input type.
	 *
	 * @param text
	 *            the source text to embed
	 * @param inputType
	 *            whether the text is a query or corpus document/chunk
	 *
	 * @return a {@link Mono} emitting the embedded vector
	 */
	Mono<VectorQueryVector> embed(
		String text,
		VectorEmbeddingInputType inputType
	);

	/**
	 * Embeds search query text.
	 *
	 * @param text
	 *            the query text
	 *
	 * @return a {@link Mono} emitting the query vector
	 */
	default Mono<VectorQueryVector> embedQuery(
		String text
	) {

		return embed( text, VectorEmbeddingInputType.QUERY );

	}

	/**
	 * Embeds corpus document or chunk text.
	 *
	 * @param text
	 *            the document text
	 *
	 * @return a {@link Mono} emitting the document vector
	 */
	default Mono<VectorQueryVector> embedDocument(
		String text
	) {

		return embed( text, VectorEmbeddingInputType.DOCUMENT );

	}

	/**
	 * Embeds corpus document or chunk texts sequentially.
	 *
	 * @param texts
	 *            the document texts
	 *
	 * @return a {@link Flux} emitting document vectors in input order
	 */
	default Flux<VectorQueryVector> embedDocuments(
		Collection<String> texts
	) {

		Objects.requireNonNull( texts, "texts" );
		return Flux.fromIterable( texts ).concatMap( this::embedDocument );

	}

}
