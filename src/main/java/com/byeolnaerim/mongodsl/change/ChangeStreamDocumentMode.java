package com.byeolnaerim.mongodsl.change;


/**
 * Controls how much document image information the shared MongoDB change stream requests.
 * <p>{@link #AUTO} is backward-compatible: MongoDB 6.0+ uses post-images when available,
 * while older servers retain the driver's delta-only change stream. Explicit modes remain
 * available when an application wants to force a particular server contract.</p>
 */
public enum ChangeStreamDocumentMode {

	/** Use post-images on MongoDB 6.0+ and DELTA on older servers. */
	AUTO,

	/** Preserve the driver's default change-stream payload. */
	DELTA,

	/** Request only post-images when enabled on the collection. Requires MongoDB 6.0+. */
	POST_IMAGE_WHEN_AVAILABLE,

	/** Request pre/post images when the watched collection has them enabled. Requires MongoDB 6.0+. */
	PRE_POST_WHEN_AVAILABLE,

	/** Request the driver's updateLookup post-image for UPDATE events without requesting pre-images. */
	UPDATE_LOOKUP

}
