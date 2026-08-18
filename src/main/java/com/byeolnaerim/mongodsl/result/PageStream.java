package com.byeolnaerim.mongodsl.result;


import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


/**
 * Reactive page wrapper that exposes page data as a {@link Flux}
 * and the total count as a {@link Mono}.
 *
 * @param <T>
 *            the element type
 */
public final class PageStream<T> {

	private final Flux<T> data;

	private final Mono<Long> totalCount;

	/**
	 * Creates a reactive page wrapper from the given data stream and total-count publisher.
	 *
	 * @param data
	 *            the page data stream
	 * @param totalCount
	 *            the total-count publisher
	 */
	public PageStream(
						Flux<T> data,
						Mono<Long> totalCount
	) {

		this.data = (data == null) ? Flux.empty() : data;
		this.totalCount = (totalCount == null) ? Mono.just( 0L ) : totalCount;

	}

	/**
	 * Creates a reactive page wrapper from the given data stream and known total count.
	 * totalCount를 바로 알고 있을 때 편의 생성자
	 *
	 * @param data
	 *            the page data stream
	 * @param totalCount
	 *            the total number of matching items
	 */
	public PageStream(
						Flux<T> data,
						long totalCount
	) {

		this( data, Mono.just( totalCount ) );

	}

	/**
	 * Returns the current page data stream.
	 *
	 * @return the page data stream
	 */
	public Flux<T> data() {

		return data;

	}

	/**
	 * Returns the total number of matching items.
	 *
	 * @return the total-count publisher
	 */
	public Mono<Long> totalCount() {

		return totalCount;

	}


	/**
	 * Returns whether the page is empty based on {@code totalCount}.
	 * totalCount 기준으로 비어있는지 여부
	 * 
	 * @return a {@link Mono} emitting {@code true} if no matching items exist
	 */
	public Mono<Boolean> isEmpty() { return totalCount
		.defaultIfEmpty( 0L )
		.map( tc -> tc == 0L ); }

	/**
	 * Counts the number of items emitted by the current page data stream.
	 * <p>This is the current page size, not the overall total count.</p>
	 * 현재 페이지(data Flux)가 몇 개를 내보내는지 카운트.
	 * (PageResult의 size()에 대응, 전체 totalCount가 아니라 "현재 페이지 크기")
	 *
	 * @return a {@link Mono} emitting the current page size
	 */
	public Mono<Long> size() {

		return data.count();

	}

	/**
	 * Maps each item in the page data stream while preserving the original total count.
	 * 각 요소를 다른 타입으로 매핑 (totalCount는 그대로 유지)
	 * 
	 * @param mapper
	 *            the mapping function
	 * @param <R>
	 *            the target element type
	 * 
	 * @return a mapped page stream
	 */
	public <R> PageStream<R> map(
		Function<? super T, ? extends R> mapper
	) {

		Objects.requireNonNull( mapper, "mapper" );
		return new PageStream<>( data.map( mapper ), totalCount );

	}

	/**
	 * Maps each item in the page data stream and removes {@code null} mapping results.
	 * 
	 * @param mapper
	 *            the mapping function
	 * @param <R>
	 *            the target element type
	 * 
	 * @return a mapped page stream without null values
	 */
	public <R> PageStream<R> mapNotNull(
		Function<? super T, ? extends R> mapper
	) {

		Objects.requireNonNull( mapper, "mapper" );

		return new PageStream<>(
			data
				.<R>map( mapper )
				.filter( Objects::nonNull ),
			totalCount
		);

	}

	/**
	 * Filters the page data stream while preserving the original total count.
	 * 
	 * @param predicate
	 *            the filter predicate
	 * 
	 * @return a filtered page stream
	 */
	public PageStream<T> filter(
		Predicate<? super T> predicate
	) {

		Objects.requireNonNull( predicate, "predicate" );
		return new PageStream<>(
			data.filter( predicate::test ),
			totalCount
		);

	}

	/**
	 * Removes {@code null} items from the page data stream.
	 *
	 * @return a filtered page stream without null values
	 */
	public PageStream<T> filterNotNull() {

		return new PageStream<>(
			data.filter( Objects::nonNull ),
			totalCount
		);

	}

	/**
	 * Performs a side effect for each emitted item and returns a new {@link PageStream}.
	 * 각 요소에 대해 부수효과를 수행하고, 다시 PageStream 으로 돌려줌 (체이닝용)
	 * 
	 * @param action
	 *            the side-effect action
	 * 
	 * @return a new page stream
	 */
	public PageStream<T> onEach(
		Consumer<? super T> action
	) {

		Objects.requireNonNull( action, "action" );
		return new PageStream<>(
			data.doOnNext( action ),
			totalCount
		);

	}

	/**
	 * Consumes each emitted item with the given action.
	 * 단순 소비용(forEach와 비슷) – subscribe는 호출하는 쪽에서
	 * 
	 * @param action
	 *            the action to apply
	 * 
	 * @return a {@link Mono} that completes when consumption finishes
	 */
	public Mono<Void> forEach(
		Consumer<? super T> action
	) {

		Objects.requireNonNull( action, "action" );
		return data
			.doOnNext( action )
			.then();

	}

	/**
	 * Consumes each emitted item together with its zero-based index.
	 *
	 * @param action
	 *            the indexed action to apply
	 * 
	 * @return a {@link Mono} that completes when consumption finishes
	 */
	public Mono<Void> forEachIndexed(
		BiConsumer<Integer, ? super T> action
	) {

		Objects.requireNonNull( action, "action" );
		return data
			.index()
			.doOnNext( t -> action.accept( t.getT1().intValue(), t.getT2() ) )
			.then();

	}

	/**
	 * Calculates the total number of pages for the given page size.
	 * totalCount 기준 총 페이지 수 계산 (pageSize > 0)
	 * 
	 * @param pageSize
	 *            the page size
	 * 
	 * @return a {@link Mono} emitting the total number of pages
	 */
	public Mono<Integer> totalPages(
		int pageSize
	) {

		if (pageSize <= 0) { return Mono.error( new IllegalArgumentException( "pageSize must be > 0" ) ); }

		return totalCount
			.defaultIfEmpty( 0L )
			.map( tc -> {
				if (tc == 0L)
					return 0;
				return (int) ((tc + pageSize - 1) / pageSize); // ceil

			} );

	}

	/**
	 * Returns whether another page exists after the given zero-based page index.
	 * 다음 페이지 존재 여부. page는 0-based
	 * 
	 * @param page
	 *            the current zero-based page index
	 * @param pageSize
	 *            the page size
	 * 
	 * @return a {@link Mono} emitting {@code true} if another page exists
	 */
	public Mono<Boolean> hasNext(
		int page, int pageSize
	) {

		if (page < 0 || pageSize <= 0) { return Mono.error( new IllegalArgumentException( "invalid page/pageSize" ) ); }

		return totalCount
			.defaultIfEmpty( 0L )
			.map( tc -> {
				long shown = (long) (page + 1) * pageSize;
				return shown < tc;

			} );

	}

	/**
	 * Collects this reactive page into a {@link PageResult}.
	 * 이 PageStream 을 한 번에 List로 모아서 PageResult로 변환.
	 * (PageResult API와 함께 쓰고 싶을 때)
	 * 
	 * @return a {@link Mono} emitting the collected page result
	 */
	public Mono<PageResult<T>> collectToPageResult() {

		return Mono
			.zip(
				data.collectList(),
				totalCount.defaultIfEmpty( 0L )
			)
			.map( t -> new PageResult<>( t.getT1(), t.getT2() ) );

	}

}
