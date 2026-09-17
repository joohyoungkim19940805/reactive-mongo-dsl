package com.byeolnaerim.mongodsl;


import java.util.List;
import java.util.function.Supplier;
import com.byeolnaerim.mongodsl.internal.MemoizedSupplier;
import com.byeolnaerim.mongodsl.result.ReservationDelta;


final class ReservationEmission<T> {

	private final Supplier<List<T>> snapshotSupplier;

	private final List<ReservationDelta<T>> deltas;

	private final long revision;

	ReservationEmission(
						Supplier<List<T>> snapshotSupplier,
						List<ReservationDelta<T>> deltas,
						long revision
	) {

		this.snapshotSupplier = new MemoizedSupplier<>( snapshotSupplier );
		this.deltas = List.copyOf( deltas );
		this.revision = revision;

	}

	ReservationEmission(
						List<T> snapshot,
						List<ReservationDelta<T>> deltas
	) {

		this.snapshotSupplier = new MemoizedSupplier<>( new ReservationSnapshotSupplier<>( snapshot ) );
		this.deltas = List.copyOf( deltas );
		this.revision = 0L;

	}

	ReservationEmission(
						Supplier<List<T>> snapshotSupplier,
						List<ReservationDelta<T>> deltas
	) {

		this.snapshotSupplier = new MemoizedSupplier<>( snapshotSupplier );
		this.deltas = List.copyOf( deltas );
		this.revision = 0L;

	}

	Supplier<List<T>> snapshotSupplier() {

		return snapshotSupplier;

	}

	List<ReservationDelta<T>> deltas() {

		return deltas;

	}

	long revision() {

		return revision;

	}

	List<T> snapshot() {

		return snapshotSupplier.get();

	}

}


final class ReservationSnapshotSupplier<T> implements Supplier<List<T>> {

	private final List<T> snapshot;

	ReservationSnapshotSupplier(
								List<T> snapshot
	) {

		this.snapshot = snapshot;

	}

	@Override
	public List<T> get() {

		return snapshot;

	}

}
