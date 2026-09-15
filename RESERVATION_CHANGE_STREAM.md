# Incremental reservation change streams

`reservationChangeStream()` preserves the existing `execute(): Flux<List<E>>`,
`deltas(): Flux<ReservationDelta<E>>`, `changes()` and `executeLookup(...)` APIs.
`AUTO` materializes supported ordinary `findAll()` queries and maintains them from
Change Stream documents/updates. It delegates unsafe semantics to MongoDB instead
of treating an unsupported predicate as false.

```java
Flux<ReservationDelta<Comment>> changes = mongoDsl
    .executeEntity(Comment.class, MongoKey.MAIN)
    .fields(pair("postId", postId))
    .end()
    .findAll()
    .sorts(sort -> sort.asc("createdAt").asc("_id"))
    .reservationChangeStream()
    .bufferCapacity(1024) // optional; default 1024 pending raw events
    .coalesce(Duration.ofMillis(50))
    .deltas();
```

Use a unique final sort key when the order of equal primary sort keys matters.
Without it MongoDB itself does not promise a stable tie order. Local maintenance
preserves existing tie order; it does not invent a new public sort order.

## Read consistency and bootstrap

Incremental internal snapshots and targeted `_id` reads use **primary + majority**
in a fresh causally consistent session. The external finite-query API is unchanged.
An unspecified/default concern may be upgraded for internal materialization.
Explicit or inherited non-primary preferences or non-majority concerns are not
silently overwritten: `AUTO` uses server re-query maintenance, and
`INCREMENTAL_ONLY` reports the incompatible setting. A context that cannot provide
a fresh causal session likewise cannot claim incremental correctness.

Initialization attaches to the shared hub first, then verifies collection metadata,
captures a lower logical read fence and advances the client session operation time
before the majority read. A successful read therefore covers the fence; a separate
`ping` by itself is not treated as proof that a read observed the corresponding writes.
Only events with a known timestamp at/before a successful read fence are discarded.
Later events remain eligible; missing timestamps always require a correctness refresh.

The collection must be verified as a normal **simple-collation** collection through
`listCollections`. Non-simple collation, views/time-series namespaces, missing metadata
or permission failures fall back in `AUTO`, or fail explicitly in `INCREMENTAL_ONLY`.
The metadata check is once per active shared materialization, not once per subscriber
or event. It runs after event subscription so a concurrent drop/recreate is observed.
An overflow rechecks metadata because its discarded backlog might include DDL.
Observed DDL/rename invalidates local maintenance until a fresh subscription.

These are live, convergent query views, not historical event-time snapshots or a
linearizable transaction spanning the initial query and the entire subscription.

## Local updates and targeted lookups

The supported ordinary path is:

1. Use `fullDocument` when present.
2. Otherwise patch an existing materialized `Document` using `updateDescription`.
3. For a potentially entering document or an ambiguous patch, read only its `_id`.
4. For unsupported predicates, metadata changes, page-boundary changes or overflow,
   refresh the finite query (or fail in `INCREMENTAL_ONLY`).

A missing targeted lookup removes a row. An unchanged targeted lookup emits no delta
and **does not remove** the row. Normal DELETE uses `documentKey._id` without a query;
an absent key is an unknown event requiring refresh, not permission to ignore deletion.
Array-index update paths are compared with logical filter/sort paths conservatively.
Ambiguous update application still uses a targeted lookup rather than guessing.

The matcher handles ordinary null/missing, supported scalar numeric comparisons,
membership and array predicates conservatively. Simple strings use unsigned UTF-8
ordering rather than Java UTF-16 ordering. Scalar sorting includes BSON type order,
exact mixed-number comparison, signed decimal zero and supported special values.
Regex/PCRE, nested array ambiguities, embedded-document equality, legacy undefined
semantics, opaque driver expressions, unsupported sorts and projections still delegate
to the server. `UNKNOWN` is never silently converted to `NO_MATCH`.

## Bounded buffering and slow subscribers

`bufferCapacity(n)` bounds the raw per-reservation pending-event queue. Overflow
replaces the lost backlog with an explicit invalidation marker; it never silently
continues applying a partial event history. `AUTO` refreshes from a causal read fence;
`INCREMENTAL_ONLY` fails instead of issuing the full refresh.

The optional incremental coalescer has a maximum batch of `min(256, bufferCapacity)`;
its fair backpressure mode has additional finite prefetch (up to four batches).
The hub also has its existing bounded micro-batch. Capacity counts **events**, not
bytes, and does not limit materialized query size. Large documents and many distinct
reservations still require a suitable heap budget/query limits.

Each snapshot subscriber retains at most one pending latest emission beyond any
currently consumed emission. Delta consumers check internal emission revisions: if
backpressure skipped a version, they receive `REFRESHED` with the captured current
snapshot instead of a delta relative to a state they never saw. The public raw
`changeStreams().watch(...)` event API is not changed into a lossy latest-value stream.

## Item deltas and mapping cost

Every delta subscriber first receives `INITIAL` with the snapshot current at its own
attachment, including late subscribers to a hot shared reservation. Subsequent values:

- `INSERTED`: `after` and `afterIndex`.
- `UPDATED`: `before`, `after`, `beforeIndex` and `afterIndex`.
- `REMOVED`: `before` and `beforeIndex`.
- `REFRESHED`: full state after a correctness refresh or subscriber revision gap.

For each delta in sequence, remove `beforeIndex` before inserting at `afterIndex`.
Full-state emissions replace the consumer's complete state.

Ordinary batch emissions capture immutable row references. Full entity mapping is lazy
and memoized, so an already attached delta-only consumer maps only affected rows rather
than the complete result after every update. Late INITIAL/REFRESHED and `execute()`
consumers still require full mapping. The reference snapshot itself is still O(n).

Ordered rows are maintained as a list with binary insertion-position search and
in-place updates. Initial/refresh order is taken from MongoDB. Per-event full sorting
has been removed; id-to-position scans and array-list shifts remain O(n), not O(log n).
This deliberately avoids introducing a second tree/index subsystem.

## Modes and shared maintenance

```java
.reservationChangeStream().mode(ReservationMode.AUTO)             // default
.reservationChangeStream().mode(ReservationMode.REQUERY)          // server refresh
.reservationChangeStream().mode(ReservationMode.INCREMENTAL_ONLY) // no full-query fallback
```

Targeted `_id` reads are permitted in incremental-only mode. A client-side REFRESHED
caused by subscriber backpressure uses captured state and does not itself query MongoDB.
Offset paging, explicit dependency invalidation and lookup reservations retain their
existing strict-mode restrictions.

Identical active find reservations share their materialization within the same DSL
instance and mapping-compatible execution scope. Both left and right mapping scopes,
criteria, lookup pipeline and relevant options participate in lookup sharing. Opaque
`customizeQuery`/`customizeAggregation` callbacks are never assumed equivalent.
Entries are released when the last subscriber disconnects.

Lookup reservations (including page-number-cursor lookup reservations) now share their
**server re-query** stream. They are not an in-memory join implementation. Refreshes
are single-flight: writes during an active refresh set one pending dirty flag, rather
than cancelling/restarting the active query or enqueueing one query per write.

Offset paging retains hybrid behavior: changes that can move the materialized window
refresh it; safe in-window updates may be local. Ordinary page-number-cursor
`reservationChangeStream().execute()` remains a re-query path without identical-query
registry sharing; its refresh execution is nevertheless single-flight.

## Shared document policy

`ChangeStreamDocumentMode.AUTO` probes `hello.maxWireVersion` once per shared hub scope.
On MongoDB 6+ it requests **only** `FullDocument.WHEN_AVAILABLE`. On older servers, or
failed/empty probing, it preserves the delta-only driver behavior. Unused pre-images
are no longer requested by AUTO, and enabling server-side image retention remains an
administrator decision; the library does not enable it.

Explicit policies are additive and existing policies remain available:

```java
new ReactiveMongoDsl<>(resolver, ChangeStreamDocumentMode.DELTA);
new ReactiveMongoDsl<>(resolver, ChangeStreamDocumentMode.POST_IMAGE_WHEN_AVAILABLE);
new ReactiveMongoDsl<>(resolver, ChangeStreamDocumentMode.PRE_POST_WHEN_AVAILABLE);
new ReactiveMongoDsl<>(resolver, ChangeStreamDocumentMode.UPDATE_LOOKUP);
```

`UPDATE_LOOKUP` remains opt-in. Post-image policy may increase network payload for
other watched collections too; `DELTA` is available when that tradeoff is undesirable.

## Verification

See `RESERVATION_TESTING.md` for reproducible commands and `TEST_REPORT.md` for what
was actually executed with this delivery. A parsed source file or mocked driver test
is not a substitute for the real MongoDB differential gate.
