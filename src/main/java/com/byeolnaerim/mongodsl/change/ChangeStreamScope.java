package com.byeolnaerim.mongodsl.change;


import java.util.Objects;


/**
 * Physical change-stream scope. Session scope equality is intentionally identity-based so
 * unrelated Mongo clients cannot accidentally share a local stream because of an equals
 * implementation. A separate optional distributed-state key is carried for external stores.
 */
public final class ChangeStreamScope {

	private final Object sessionScope;

	private final String databaseName;

	private final String distributedStateScopeKey;

	public ChangeStreamScope(
		Object sessionScope, String databaseName
	) {

		this( sessionScope, databaseName, null );

	}

	public ChangeStreamScope(
		Object sessionScope, String databaseName, String distributedStateScopeKey
	) {

		this.sessionScope = Objects.requireNonNull( sessionScope, "sessionScope must not be null" );
		this.databaseName = Objects.requireNonNull( databaseName, "databaseName must not be null" );
		this.distributedStateScopeKey = distributedStateScopeKey == null || distributedStateScopeKey.isBlank()
			? null
			: distributedStateScopeKey.trim();

	}

	public Object sessionScope() { return sessionScope; }

	public String databaseName() { return databaseName; }

	/** Stable cross-process scope supplied by MongoExecutionContext, or {@code null}. */
	public String distributedStateScopeKey() { return distributedStateScopeKey; }

	/** Stable key suitable for an external checkpoint/coordination store, or {@code null}. */
	public String persistentKey() {

		return distributedStateScopeKey == null ? null : distributedStateScopeKey + ":" + databaseName;

	}

	@Override
	public boolean equals(
		Object other
	) {

		return this == other
			|| other instanceof ChangeStreamScope scope
				&& this.sessionScope == scope.sessionScope
				&& this.databaseName.equals( scope.databaseName );

	}

	@Override
	public int hashCode() {

		return 31 * System.identityHashCode( sessionScope ) + databaseName.hashCode();

	}

	@Override
	public String toString() {

		return "ChangeStreamScope[databaseName=" + databaseName
			+ ", sessionScopeIdentity=" + Integer.toHexString( System.identityHashCode( sessionScope ) )
			+ ", distributedStateScopeKey=" + distributedStateScopeKey + "]";

	}

}
