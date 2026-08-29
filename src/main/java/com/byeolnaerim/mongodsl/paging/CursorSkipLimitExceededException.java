package com.byeolnaerim.mongodsl.paging;


/**
 * Raised when page-number cursor paging exceeds its configured relative-skip
 * limit and the active policy is {@link CursorSkipExceededAction#FAIL}.
 */
public final class CursorSkipLimitExceededException extends IllegalArgumentException {

	private static final long serialVersionUID = 1L;

	private final int targetPageNumber;
	private final int anchorPageNumber;
	private final int pageSize;
	private final long relativeSkip;
	private final long maxRelativeSkip;

	public CursorSkipLimitExceededException(
		int targetPageNumber,
		int anchorPageNumber,
		int pageSize,
		long relativeSkip,
		long maxRelativeSkip
	) {

		super(
			"cursor page requires relative skip " + relativeSkip
				+ " but configured maxRelativeSkip is " + maxRelativeSkip
				+ " (targetPage=" + targetPageNumber
				+ ", anchorPage=" + anchorPageNumber
				+ ", pageSize=" + pageSize + "). "
				+ "Visit nearer pages first to seed anchors, use an opaque cursor token, "
				+ "or configure paging().pageNumberCursor().skipPolicy()."
		);
		this.targetPageNumber = targetPageNumber;
		this.anchorPageNumber = anchorPageNumber;
		this.pageSize = pageSize;
		this.relativeSkip = relativeSkip;
		this.maxRelativeSkip = maxRelativeSkip;

	}

	public int targetPageNumber() { return targetPageNumber; }

	public int anchorPageNumber() { return anchorPageNumber; }

	public int pageSize() { return pageSize; }

	public long relativeSkip() { return relativeSkip; }

	public long maxRelativeSkip() { return maxRelativeSkip; }

}
