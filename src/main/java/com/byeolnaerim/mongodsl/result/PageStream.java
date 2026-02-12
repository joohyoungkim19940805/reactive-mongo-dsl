package com.byeolnaerim.mongodsl.result;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;


import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class PageStream<T> {

	private final Flux<T> data;

	private final Mono<Long> totalCount;

	public PageStream(
						Flux<T> data,
						Mono<Long> totalCount
	) {

		this.data = (data == null) ? Flux.empty() : data;
		this.totalCount = (totalCount == null) ? Mono.just( 0L ) : totalCount;

	}

	/** totalCount를 바로 알고 있을 때 편의 생성자 */
	public PageStream(
						Flux<T> data,
						long totalCount
	) {

		this( data, Mono.just( totalCount ) );

	}

	public Flux<T> data() {

		return data;

	}

	public Mono<Long> totalCount() {

		return totalCount;

	}

	// ----------------------------------------------------------------------
	// 헬퍼들 (PageResult 와 비슷한 역할, reactive 스타일로)
	// ----------------------------------------------------------------------

	/** totalCount 기준으로 비어있는지 여부 */
	public Mono<Boolean> isEmpty() { return totalCount
		.defaultIfEmpty( 0L )
		.map( tc -> tc == 0L ); }

	/**
	 * 현재 페이지(data Flux)가 몇 개를 내보내는지 카운트.
	 * (PageResult의 size()에 대응, 전체 totalCount가 아니라 "현재 페이지 크기")
	 */
	public Mono<Long> size() {

		return data.count();

	}

	/** 각 요소를 다른 타입으로 매핑 (totalCount는 그대로 유지) */
	public <R> PageStream<R> map(
		Function<? super T, ? extends R> mapper
	) {

		Objects.requireNonNull( mapper, "mapper" );
		return new PageStream<>( data.map( mapper ), totalCount );

	}

	/** null 결과는 제거하면서 매핑 */
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

	/** data 스트림을 필터링 (totalCount는 원본 값을 그대로 유지) */
	public PageStream<T> filter(
		Predicate<? super T> predicate
	) {

		Objects.requireNonNull( predicate, "predicate" );
		return new PageStream<>(
			data.filter( predicate::test ),
			totalCount
		);

	}

	/** null 요소 제거 (totalCount는 원본 유지) */
	public PageStream<T> filterNotNull() {

		return new PageStream<>(
			data.filter( Objects::nonNull ),
			totalCount
		);

	}

	/** 각 요소에 대해 부수효과를 수행하고, 다시 PageStream 으로 돌려줌 (체이닝용) */
	public PageStream<T> onEach(
		Consumer<? super T> action
	) {

		Objects.requireNonNull( action, "action" );
		return new PageStream<>(
			data.doOnNext( action ),
			totalCount
		);

	}

	/** 단순 소비용(forEach와 비슷) – subscribe는 호출하는 쪽에서 */
	public Mono<Void> forEach(
		Consumer<? super T> action
	) {

		Objects.requireNonNull( action, "action" );
		return data
			.doOnNext( action )
			.then();

	}

	/** 인덱스를 함께 쓰는 순회 (PageResult.forEachIndexed 대응) */
	public Mono<Void> forEachIndexed(
		BiConsumer<Integer, ? super T> action
	) {

		Objects.requireNonNull( action, "action" );
		return data
			.index()
			.doOnNext( t -> action.accept( t.getT1().intValue(), t.getT2() ) )
			.then();

	}

	/** totalCount 기준 총 페이지 수 계산 (pageSize > 0) */
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

	/** 다음 페이지 존재 여부. page는 0-based */
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
	 * 이 PageStream 을 한 번에 List로 모아서 PageResult로 변환.
	 * (전통적인 PageResult API와 함께 쓰고 싶을 때)
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
