package com.byeolnaerim.mongodsl.result;


/** Logical change emitted by an incrementally maintained reservation query. */
public enum ReservationDeltaType {
	INITIAL,
	INSERTED,
	UPDATED,
	REMOVED,
	REFRESHED
}
