package com.byeolnaerim.mongodsl.result;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Simple page result container that holds the current page data and
 * the total number of matching items.
 *
 * @param <E>
 *            the element type
 */
public class PageResult<E> {

	private final List<E> data;

	private final Long totalCount;

	/**
	 * Returns the current page data.
	 *
	 * @return the page data
	 */
	public List<E> getData() { return this.data; }

	/**
	 * Returns the total number of matching items.
	 *
	 * @return the total count
	 */
	public Long getTotalCount() { return this.totalCount; }

	/**
	 * Creates an empty page result.
	 */
	public PageResult() {

		this.data = Collections.emptyList();
		this.totalCount = 0L;

	}

	/**
	 * Creates an empty page result with the given total count.
	 *
	 * @param totalCount
	 *            the total number of matching items
	 */
	public PageResult(
						Long totalCount
	) {

		this.data = Collections.emptyList();
		this.totalCount = totalCount;

	}

	/**
	 * Creates a page result with the given data and a default total count of {@code 0}.
	 *
	 * @param data
	 *            the page data
	 */
	public PageResult(
						List<E> data
	) {

		this.data = data;
		this.totalCount = 0L;

	}

	/**
	 * Creates a page result with the given data and total count.
	 *
	 * @param data
	 *            the page data
	 * @param totalCount
	 *            the total number of matching items
	 */
	public PageResult(
						List<E> data,
						Long totalCount
	) {

		this.data = data;
		this.totalCount = totalCount;

	}

	/**
	 * Returns whether the current page contains no items.
	 *
	 * @return {@code true} if the current page data is empty
	 */
	public boolean isEmpty() { return data == null || data.isEmpty(); }

	/**
	 * Returns the number of items in the current page data.
	 *
	 * @return the current page size
	 */
	public int size() {

		return data == null ? 0 : data.size();

	}

	/**
	 * Returns an empty {@link PageResult}.
	 *
	 * @param <E>
	 *            the element type
	 * 
	 * @return an empty page result
	 */
	public static <E> PageResult<E> empty() {

		return new PageResult<>( Collections.emptyList(), 0L );

	}

	/**
	 * Creates a {@link PageResult} with the given data and total count.
	 *
	 * @param data
	 *            the page data
	 * @param totalCount
	 *            the total number of matching items
	 * @param <E>
	 *            the element type
	 * 
	 * @return a new page result
	 */
	public static <E> PageResult<E> of(
		List<E> data, long totalCount
	) {

		return new PageResult<>( data, totalCount );

	}

	/**
	 * Maps the current page data while preserving the original total count.
	 *
	 * @param mapper
	 *            the mapping function
	 * @param <R>
	 *            the target element type
	 * 
	 * @return a mapped page result
	 */
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

	/**
	 * Maps the current page data and removes {@code null} mapping results
	 * while preserving the original total count.
	 *
	 * @param mapper
	 *            the mapping function
	 * @param <R>
	 *            the target element type
	 * 
	 * @return a mapped page result without null values
	 */
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

	/**
	 * Returns the total number of pages for the given page size.
	 * 총 페이지 수 (pageSize > 0 필요). totalCount가 null이면 0으로 가정
	 *
	 * @param pageSize
	 *            the page size
	 * 
	 * @return the total number of pages
	 */
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

	/**
	 * Returns whether another page exists after the given zero-based page index.
	 * 다음 페이지 존재 여부. page는 0-based. totalCount가 null이면 false로 가정
	 *
	 * @param page
	 *            the current zero-based page index
	 * @param pageSize
	 *            the page size
	 * 
	 * @return {@code true} if another page exists
	 */
	public boolean hasNext(
		int page, int pageSize
	) {

		if (page < 0 || pageSize <= 0)
			throw new IllegalArgumentException( "invalid page/pageSize" );
		long tc = (totalCount == null) ? 0L : totalCount;
		long shown = (long) (page + 1) * pageSize;
		return shown < tc;

	}

	/**
	 * Returns an unmodifiable view of the current page data.
	 * 외부에서 리스트를 변경하지 못하게 하는 읽기 전용 뷰
	 *
	 * @return an unmodifiable data view
	 */
	public List<E> asUnmodifiableData() {

		return Collections.unmodifiableList( data == null ? Collections.emptyList() : data );

	}

	/**
	 * Filters the current page data while preserving the original total count.
	 * 조건으로 data를 필터링 (totalCount는 원본 값 유지)
	 *
	 * @param predicate
	 *            the filter predicate
	 * 
	 * @return a filtered page result
	 */
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

	/**
	 * Removes {@code null} elements from the current page data while preserving the original total
	 * count.
	 * null 요소 제거 (totalCount는 원본 유지).
	 * 
	 * @return a filtered page result without null values
	 */
	public PageResult<E> filterNotNull() {

		List<E> filtered = (data == null ? Collections.<E>emptyList() : data)
			.stream()
			.filter( Objects::nonNull )
			.collect( Collectors.toList() );
		return new PageResult<>( filtered, totalCount );

	}

	/**
	 * Performs the given action for each item in the current page data.
	 * 각 요소에 대해 작업 수행 (체이닝이 필요 없을 때)
	 *
	 * @param action
	 *            the action to perform
	 */
	public void forEach(
		Consumer<? super E> action
	) {

		Objects.requireNonNull( action, "action" );
		if (data != null)
			data.forEach( action );

	}

	/**
	 * Performs the given action for each item in the current page data and returns this result object.
	 * 각 요소에 대해 작업 수행 후 this 반환 (체이닝용)
	 * 
	 * @param action
	 *            the action to perform
	 * 
	 * @return this page result
	 */
	public PageResult<E> onEach(
		Consumer<? super E> action
	) {

		Objects.requireNonNull( action, "action" );
		if (data != null)
			data.forEach( action );
		return this;

	}

	/**
	 * Performs the given action for each item together with its zero-based index.
	 * 인덱스가 필요한 순회
	 * 
	 * @param action
	 *            the indexed action to perform
	 */
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
