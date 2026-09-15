package com.byeolnaerim.mongodsl.internal;


import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.UnaryOperator;


/**
 * A bounded event queue that replaces a lost backlog with an explicit invalidation, never with
 * a silently missing delta. The consumer must refresh its state when it receives that marker.
 * All queue operations are synchronized because an overflow also mutates the consumer end.
 */
public final class BoundedInvalidationQueue<T> extends AbstractQueue<T> {

	private final int capacity;
	private final UnaryOperator<T> invalidation;
	private final ArrayDeque<T> queue = new ArrayDeque<>();
	private long overflowCount;

	public BoundedInvalidationQueue(
		int capacity, UnaryOperator<T> invalidation
	) {
		if (capacity < 1)
			throw new IllegalArgumentException( "capacity must be >= 1" );
		this.capacity = capacity;
		this.invalidation = Objects.requireNonNull( invalidation, "invalidation must not be null" );
	}

	@Override
	public synchronized boolean offer(
		T value
	) {
		Objects.requireNonNull( value, "value must not be null" );
		if (queue.size() >= capacity) {
			T marker = Objects.requireNonNull( invalidation.apply( value ), "invalidation must not return null" );
			queue.clear();
			queue.addLast( marker );
			overflowCount++;
		} else {
			queue.addLast( value );
		}
		return true;
	}

	@Override
	public synchronized T poll() { return queue.pollFirst(); }

	@Override
	public synchronized T peek() { return queue.peekFirst(); }

	@Override
	public synchronized int size() { return queue.size(); }

	@Override
	public synchronized void clear() { queue.clear(); }

	@Override
	public synchronized Iterator<T> iterator() { return List.copyOf( queue ).iterator(); }

	public synchronized long overflowCount() { return overflowCount; }

}
