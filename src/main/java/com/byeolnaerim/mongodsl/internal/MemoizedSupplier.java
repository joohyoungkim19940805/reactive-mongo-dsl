package com.byeolnaerim.mongodsl.internal;


import java.util.Objects;
import java.util.function.Supplier;


/** Thread-safe, non-null lazy value; releases the captured inputs after successful evaluation. */
public final class MemoizedSupplier<T> implements Supplier<T> {

	private Supplier<T> source;
	private T value;

	public MemoizedSupplier(
		Supplier<T> source
	) {
		this.source = Objects.requireNonNull( source, "source must not be null" );
	}

	@Override
	public synchronized T get() {
		if (source != null) {
			value = Objects.requireNonNull( source.get(), "source must not return null" );
			source = null;
		}
		return value;
	}

}
