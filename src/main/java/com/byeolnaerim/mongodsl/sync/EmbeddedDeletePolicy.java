package com.byeolnaerim.mongodsl.sync;


/** Behavior applied to embedded snapshots when their canonical source document is deleted. */
public enum EmbeddedDeletePolicy {
	REMOVE,
	IGNORE
}
