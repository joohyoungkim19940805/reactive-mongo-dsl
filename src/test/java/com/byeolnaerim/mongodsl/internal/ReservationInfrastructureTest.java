package com.byeolnaerim.mongodsl.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReservationInfrastructureTest {

    @Test
    void overflowProducesInvalidationInsteadOfSilentlyLosingEvents() {
        var queue = new BoundedInvalidationQueue<Integer>(3, ignored -> -1);
        queue.addAll(List.of(1, 2, 3));
        queue.offer(4);
        queue.offer(5);
        assertEquals(List.of(-1, 5), List.copyOf(queue));
        assertEquals(1, queue.overflowCount());
        assertEquals(-1, queue.poll());
        assertEquals(5, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void sustainedBurstIsBoundedAndDoesNotEraseInvalidation() {
        var queue = new BoundedInvalidationQueue<Integer>(32, ignored -> -1);
        for (int i = 0; i < 100_000; i++) {
            queue.offer(i);
            assertTrue(queue.size() <= 32);
        }
        assertEquals(-1, queue.peek());
        assertTrue(queue.overflowCount() > 0);
    }

    @Test
    void invalidQueueArgumentsFailBeforeMutatingBacklog() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedInvalidationQueue<>(0, value -> value));
        var queue = new BoundedInvalidationQueue<Integer>(1, ignored -> null);
        queue.offer(1);
        assertThrows(NullPointerException.class, () -> queue.offer(2));
        assertEquals(1, queue.poll());
        assertThrows(NullPointerException.class, () -> queue.offer(null));
    }

    @Test
    void snapshotMappingIsLazyAndMemoizedAcrossConcurrentConsumers() throws Exception {
        AtomicInteger mappings = new AtomicInteger();
        var supplier = new MemoizedSupplier<>(() -> {
            mappings.incrementAndGet();
            return List.of(1, 2, 3);
        });
        assertEquals(0, mappings.get());
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<List<Integer>>>();
            for (int i = 0; i < 100; i++) futures.add(executor.submit(supplier::get));
            for (var future : futures) assertSame(supplier.get(), future.get());
        }
        assertEquals(1, mappings.get());
    }

    @Test
    void utf8SimpleOrderDiffersFromJavaUtf16AtSupplementaryCharacters() {
        assertTrue("\uE000".compareTo("\uD800\uDC00") > 0);
        assertTrue(MongoSimpleCollation.compare("\uE000", "\uD800\uDC00") < 0);
        assertEquals(0, MongoSimpleCollation.compare("\uD55C\uAE00", "\uD55C\uAE00"));
    }

    @Test
    void simpleComparatorMatchesUnsignedUtf8ForDeterministicRandomStrings() {
        Random random = new Random(0xB50);
        for (int i = 0; i < 20_000; i++) {
            // Build strings by code point, also exercising lone surrogate replacement behavior.
            var a = new StringBuilder();
            var b = new StringBuilder();
            for (int j = random.nextInt(12); j > 0; j--) a.appendCodePoint(random.nextInt(0x110000));
            for (int j = random.nextInt(12); j > 0; j--) b.appendCodePoint(random.nextInt(0x110000));
            String left = a.toString();
            String right = b.toString();
            assertEquals(Integer.signum(Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8))),
                Integer.signum(MongoSimpleCollation.compare(left, right)));
        }
    }
}
