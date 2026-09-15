package com.byeolnaerim.mongodsl;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import com.byeolnaerim.mongodsl.change.ChangeStreamDocumentMode;
import com.byeolnaerim.mongodsl.change.ChangeStreamHub;
import com.byeolnaerim.mongodsl.change.InMemoryChangeStreamCheckpointStore;
import com.byeolnaerim.mongodsl.support.ReservationTestContext;
import com.mongodb.client.model.changestream.FullDocument;

class ChangeStreamDocumentModeTest {
    @Test
    void autoRequestsPostImageOnlyOnModernServers() {
        var context = new ReservationTestContext();
        try (var hub = new ChangeStreamHub()) {
            var subscription = hub.watch(context).subscribe();
            try {
                assertEquals(FullDocument.WHEN_AVAILABLE, context.fullDocumentMode.get());
                assertEquals(0, context.preImageRequests.get());
            } finally { subscription.dispose(); }
        }
    }
    @Test
    void autoPreservesDeltaOnlyContractOnOlderServers() {
        var context = new ReservationTestContext();
        context.maxWireVersion = 13;
        try (var hub = new ChangeStreamHub()) {
            var subscription = hub.watch(context).subscribe();
            try { assertNull(context.fullDocumentMode.get()); assertEquals(0, context.preImageRequests.get()); }
            finally { subscription.dispose(); }
        }
    }
    @Test
    void explicitPrePostModeRemainsAvailable() {
        var context = new ReservationTestContext();
        try (var hub = new ChangeStreamHub(new InMemoryChangeStreamCheckpointStore(), ChangeStreamDocumentMode.PRE_POST_WHEN_AVAILABLE)) {
            var subscription = hub.watch(context).subscribe();
            try { assertEquals(FullDocument.WHEN_AVAILABLE, context.fullDocumentMode.get()); assertEquals(1, context.preImageRequests.get()); }
            finally { subscription.dispose(); }
        }
    }
}
