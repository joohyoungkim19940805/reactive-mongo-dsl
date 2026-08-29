package com.byeolnaerim.mongodsl.paging;


import java.time.Duration;


/** Configuration for cursor state caching and cursor-query safety limits. */
public record CursorCacheOptions(
	Duration admissionWindow,
	int admissionThreshold,
	Duration idleTtl,
	int maxQueries,
	int maxAnchorsPerQuery,
	long deepPageSkipThreshold,
	Duration expirationTick,
	int expirationWheelSize,
	long maxRelativeSkip,
	CursorSkipExceededAction skipExceededAction,
	int maxPageSize,
	Duration tokenTtl
) {

	/**
	 * Source-compatible constructor for the original cache-only option set.
	 * New cursor safety settings use their default values.
	 */
	public CursorCacheOptions(
		Duration admissionWindow,
		int admissionThreshold,
		Duration idleTtl,
		int maxQueries,
		int maxAnchorsPerQuery,
		long deepPageSkipThreshold,
		Duration expirationTick,
		int expirationWheelSize
	) {

		this(
			admissionWindow,
			admissionThreshold,
			idleTtl,
			maxQueries,
			maxAnchorsPerQuery,
			deepPageSkipThreshold,
			expirationTick,
			expirationWheelSize,
			5_000L,
			CursorSkipExceededAction.FAIL,
			500,
			Duration.ofMinutes( 10 )
		);

	}

	/**
	 * Source-compatible constructor for the previous cursor safety option set.
	 * The relative-skip limit uses {@link CursorSkipExceededAction#FAIL}.
	 */
	public CursorCacheOptions(
		Duration admissionWindow,
		int admissionThreshold,
		Duration idleTtl,
		int maxQueries,
		int maxAnchorsPerQuery,
		long deepPageSkipThreshold,
		Duration expirationTick,
		int expirationWheelSize,
		long maxRelativeSkip,
		int maxPageSize,
		Duration tokenTtl
	) {

		this(
			admissionWindow,
			admissionThreshold,
			idleTtl,
			maxQueries,
			maxAnchorsPerQuery,
			deepPageSkipThreshold,
			expirationTick,
			expirationWheelSize,
			maxRelativeSkip,
			CursorSkipExceededAction.FAIL,
			maxPageSize,
			tokenTtl
		);

	}

	public CursorCacheOptions {

		if (admissionWindow == null || admissionWindow.isZero() || admissionWindow.isNegative())
			throw new IllegalArgumentException( "admissionWindow must be > 0" );
		if (admissionThreshold <= 0)
			throw new IllegalArgumentException( "admissionThreshold must be > 0" );
		if (idleTtl == null || idleTtl.isZero() || idleTtl.isNegative())
			throw new IllegalArgumentException( "idleTtl must be > 0" );
		if (maxQueries <= 0 || maxAnchorsPerQuery <= 0)
			throw new IllegalArgumentException( "cache limits must be > 0" );
		if (deepPageSkipThreshold < 0L)
			throw new IllegalArgumentException( "deepPageSkipThreshold must be >= 0" );
		if (expirationTick == null || expirationTick.isZero() || expirationTick.isNegative())
			throw new IllegalArgumentException( "expirationTick must be > 0" );
		if (expirationWheelSize < 8)
			throw new IllegalArgumentException( "expirationWheelSize must be >= 8" );
		if (maxRelativeSkip < 0L)
			throw new IllegalArgumentException( "maxRelativeSkip must be >= 0" );
		if (skipExceededAction == null)
			throw new IllegalArgumentException( "skipExceededAction must not be null" );
		if (maxPageSize <= 0)
			throw new IllegalArgumentException( "maxPageSize must be > 0" );
		if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative())
			throw new IllegalArgumentException( "tokenTtl must be > 0" );

	}

	/** Returns a copy with cursor-query safety limits replaced. */
	public CursorCacheOptions withSafety(
		long maxRelativeSkip, int maxPageSize, Duration tokenTtl
	) {

		return new CursorCacheOptions(
			admissionWindow,
			admissionThreshold,
			idleTtl,
			maxQueries,
			maxAnchorsPerQuery,
			deepPageSkipThreshold,
			expirationTick,
			expirationWheelSize,
			maxRelativeSkip,
			skipExceededAction,
			maxPageSize,
			tokenTtl
		);

	}

	/** Returns a copy with cursor safety limits and the skip-limit action replaced. */
	public CursorCacheOptions withSafety(
		long maxRelativeSkip, CursorSkipExceededAction skipExceededAction, int maxPageSize, Duration tokenTtl
	) {

		return new CursorCacheOptions(
			admissionWindow,
			admissionThreshold,
			idleTtl,
			maxQueries,
			maxAnchorsPerQuery,
			deepPageSkipThreshold,
			expirationTick,
			expirationWheelSize,
			maxRelativeSkip,
			skipExceededAction,
			maxPageSize,
			tokenTtl
		);

	}

	/** Returns a copy with only the page-number cursor skip policy replaced. */
	public CursorCacheOptions withCursorSkipPolicy(
		long maxRelativeSkip, CursorSkipExceededAction skipExceededAction
	) {

		return withSafety( maxRelativeSkip, skipExceededAction, maxPageSize, tokenTtl );

	}

	public static CursorCacheOptions defaults() {

		return new CursorCacheOptions(
			Duration.ofSeconds( 10 ),
			4,
			Duration.ofMinutes( 1 ),
			10_000,
			256,
			5_000L,
			Duration.ofSeconds( 1 ),
			512,
			5_000L,
			CursorSkipExceededAction.FAIL,
			500,
			Duration.ofMinutes( 10 )
		);

	}

}
