package com.byeolnaerim.mongodsl.result;

import java.util.List;

public class ResultTuple<L, R> {

	private String leftName; // 현재 쿼리 빌더의 executeClass 이름

	private L left; // 현재 쿼리 빌더의 결과 (엔티티 1개 또는 리스트)

	private String rightName; // 매개변수 빌더의 executeClass 이름

	private R right; // 매개변수 빌더의 결과 (엔티티 1개 또는 리스트)

	private Long totalCount;

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

	public String getLeftName() { return leftName; }

	public void setLeftName(
		String leftName
	) { this.leftName = leftName; }

	public L getLeft() { return left; }

	public void setLeft(
		L left
	) { this.left = left; }

	public String getRightName() { return rightName; }

	public void setRightName(
		String rightName
	) { this.rightName = rightName; }

	public R getRight() { return right; }

	public void setRight(
		R right
	) { this.right = right; }

	public Long getTotalCount() { return totalCount; }

	public void setTotalCount(
		Long totalCount
	) { this.totalCount = totalCount; }


	@SuppressWarnings("unchecked")
	public <T> T getRightIfListFirst() {

		if (right == null)
			return null;

		if (right instanceof List<?> list) { return list.isEmpty() ? null : (T) list.get( 0 ); }

		return (T) right;

	}

}

