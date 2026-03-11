package com.byeolnaerim.mongodsl.result;

import java.util.List;


/**
 * Container for paired left and right results produced by lookup
 * or other tuple-based query operations.
 *
 * @param <L>
 *            the left-side result type
 * @param <R>
 *            the right-side result type
 */
public class ResultTuple<L, R> {

	private String leftName; // 현재 쿼리 빌더의 executeClass 이름

	private L left; // 현재 쿼리 빌더의 결과 (엔티티 1개 또는 리스트)

	private String rightName; // 매개변수 빌더의 executeClass 이름

	private R right; // 매개변수 빌더의 결과 (엔티티 1개 또는 리스트)

	private Long totalCount;

	/**
	 * Creates a tuple with left and right result names and values.
	 *
	 * @param leftName
	 *            the logical name of the left result
	 * @param left
	 *            the left result value
	 * @param rightName
	 *            the logical name of the right result
	 * @param right
	 *            the right result value
	 */
	public ResultTuple(
						String leftName,
						L left,
						String rightName,
						R right
	) {

		this.leftName = leftName;
		this.left = left;
		this.rightName = rightName;
		this.right = right;

	}

	/**
	 * Creates a tuple with left and right result names and values,
	 * together with an optional total count.
	 *
	 * @param leftName
	 *            the logical name of the left result
	 * @param left
	 *            the left result value
	 * @param rightName
	 *            the logical name of the right result
	 * @param right
	 *            the right result value
	 * @param totalCount
	 *            the optional total count associated with this tuple
	 */
	public ResultTuple(
						String leftName,
						L left,
						String rightName,
						R right,
						Long totalCount
	) {

		this.leftName = leftName;
		this.left = left;
		this.rightName = rightName;
		this.right = right;
		this.totalCount = totalCount;

	}

	/**
	 * Returns the logical name of the left result.
	 *
	 * @return the left result name
	 */
	public String getLeftName() { return leftName; }

	/**
	 * Sets the logical name of the left result.
	 *
	 * @param leftName
	 *            the left result name
	 */
	public void setLeftName(
		String leftName
	) { this.leftName = leftName; }


	/**
	 * Returns the left result value.
	 *
	 * @return the left result
	 */
	public L getLeft() { return left; }

	/**
	 * Sets the left result value.
	 *
	 * @param left
	 *            the left result
	 */
	public void setLeft(
		L left
	) { this.left = left; }

	/**
	 * Returns the logical name of the right result.
	 *
	 * @return the right result name
	 */
	public String getRightName() { return rightName; }

	/**
	 * Sets the logical name of the right result.
	 *
	 * @param rightName
	 *            the right result name
	 */
	public void setRightName(
		String rightName
	) { this.rightName = rightName; }

	/**
	 * Returns the right result value.
	 *
	 * @return the right result
	 */
	public R getRight() { return right; }

	/**
	 * Sets the right result value.
	 *
	 * @param right
	 *            the right result
	 */
	public void setRight(
		R right
	) { this.right = right; }

	/**
	 * Returns the optional total count associated with this tuple.
	 *
	 * @return the total count
	 */
	public Long getTotalCount() { return totalCount; }

	/**
	 * Returns the optional total count associated with this tuple.
	 *
	 * @return the total count
	 */
	public void setTotalCount(
		Long totalCount
	) { this.totalCount = totalCount; }


	/**
	 * Returns the right value itself, or the first element if the right value is a {@link List}.
	 * <p>Returns {@code null} when the right side is {@code null} or an empty list.</p>
	 *
	 * @param <T>
	 *            the expected extracted type
	 * 
	 * @return the right value or the first list element
	 */
	@SuppressWarnings("unchecked")
	public <T> T getRightIfListFirst() {

		if (right == null)
			return null;

		if (right instanceof List<?> list) { return list.isEmpty() ? null : (T) list.get( 0 ); }

		return (T) right;

	}

}

