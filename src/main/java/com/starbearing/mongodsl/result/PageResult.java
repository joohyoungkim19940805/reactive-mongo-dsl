package com.starbearing.mongodsl.result;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;


public  class PageResult<E> {

	private final List<E> data;

	private final Long totalCount;

	public List<E> getData() { return this.data; }

	public Long getTotalCount() { return this.totalCount; }

	public PageResult() {

		this.data = Collections.emptyList();
		this.totalCount = 0L;

	}

	public PageResult(
						Long totalCount
	) {

		this.data = Collections.emptyList();
		this.totalCount = totalCount;

	}

	public PageResult(
						List<E> data
	) {

		this.data = data;
		this.totalCount = 0L;

	}

	public PageResult(
						List<E> data,
						Long totalCount
	) {

		this.data = data;
		this.totalCount = totalCount;

	}

	public boolean isEmpty() { return data == null || data.isEmpty(); }

	public int size() {

		return data == null ? 0 : data.size();

	}

	public static <E> PageResult<E> empty() {

		return new PageResult<>( Collections.emptyList(), 0L );

	}

	public static <E> PageResult<E> of(
		List<E> data, long totalCount
	) {

		return new PageResult<>( data, totalCount );

	}

	public <R> PageResult<R> map(
		Function<? super E, ? extends R> mapper
	) {

		Objects.requireNonNull( mapper, "mapper" );
		List<R> mapped = (data == null ? Collections.<E>emptyList() : data)
			.stream()
			.map( mapper )
			.collect( Collectors.toList() );
		return new PageResult<>( mapped, totalCount );

	}

	/** 총 페이지 수 (pageSize > 0 필요). totalCount가 null이면 0으로 가정 */
	public int totalPages(
		int pageSize
	) {

		if (pageSize <= 0)
			throw new IllegalArgumentException( "pageSize must be > 0" );
		long tc = (totalCount == null) ? 0L : totalCount;
		if (tc == 0L)
			return 0;
		return (int) ((tc + pageSize - 1) / pageSize); // ceil

	}

	/** 다음 페이지 존재 여부. page는 0-based. totalCount가 null이면 false로 가정 */
	public boolean hasNext(
		int page, int pageSize
	) {

		if (page < 0 || pageSize <= 0)
			throw new IllegalArgumentException( "invalid page/pageSize" );
		long tc = (totalCount == null) ? 0L : totalCount;
		long shown = (long) (page + 1) * pageSize;
		return shown < tc;

	}

	public <R> PageResult<R> mapNotNull(
		Function<? super E, ? extends R> mapper
	) {

		Objects.requireNonNull( mapper, "mapper" );
		List<R> mapped = (data == null ? Collections.<E>emptyList() : data)
			.stream()
			.map( mapper )
			.filter( Objects::nonNull )
			.collect( Collectors.toList() );
		return new PageResult<>( mapped, totalCount );

	}

	/** 외부에서 리스트를 변경하지 못하게 하는 읽기 전용 뷰 */
	public List<E> asUnmodifiableData() {

		return Collections.unmodifiableList( data == null ? Collections.emptyList() : data );

	}

	/** 조건으로 data를 필터링 (totalCount는 원본 값 유지) */
	public PageResult<E> filter(
		Predicate<? super E> predicate
	) {

		Objects.requireNonNull( predicate, "predicate" );
		List<E> filtered = (data == null ? Collections.<E>emptyList() : data)
			.stream()
			.filter( predicate )
			.collect( Collectors.toList() );
		return new PageResult<>( filtered, totalCount );

	}

	/** 조건으로 data를 필터링하고 totalCount를 재계산 */
	public PageResult<E> filterAndRecount(
		Predicate<? super E> predicate
	) {

		Objects.requireNonNull( predicate, "predicate" );
		List<E> filtered = (data == null ? Collections.<E>emptyList() : data)
			.stream()
			.filter( predicate )
			.collect( Collectors.toList() );
		return new PageResult<>( filtered, (long) filtered.size() );

	}

	/** null 요소 제거 (totalCount는 원본 유지). 필요 없으면 생략 가능 */
	public PageResult<E> filterNotNull() {

		List<E> filtered = (data == null ? Collections.<E>emptyList() : data)
			.stream()
			.filter( Objects::nonNull )
			.collect( Collectors.toList() );
		return new PageResult<>( filtered, totalCount );

	}

	/** 각 요소에 대해 작업 수행 (체이닝이 필요 없을 때) */
	public void forEach(
		Consumer<? super E> action
	) {

		Objects.requireNonNull( action, "action" );
		if (data != null)
			data.forEach( action );

	}

	/** 각 요소에 대해 작업 수행 후 this 반환 (체이닝용) */
	public PageResult<E> onEach(
		Consumer<? super E> action
	) {

		Objects.requireNonNull( action, "action" );
		if (data != null)
			data.forEach( action );
		return this;

	}

	/** 인덱스가 필요한 순회 */
	public void forEachIndexed(
		BiConsumer<Integer, ? super E> action
	) {

		Objects.requireNonNull( action, "action" );
		if (data == null)
			return;

		for (int i = 0; i < data.size(); i++) {
			action.accept( i, data.get( i ) );

		}

	}

}
