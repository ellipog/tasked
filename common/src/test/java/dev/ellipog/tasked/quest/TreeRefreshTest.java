package dev.ellipog.tasked.quest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coalescing itself, without a server: any number of requests, one refresh.
 *
 * <p>The refresh is a runnable here rather than a call, which is the whole reason the flag is its own
 * class — a burst of edits can be driven through it in a test JVM.
 */
class TreeRefreshTest {

    @Test
    @DisplayName("any number of requests costs one flush, and a clean tree is not flushed")
    void requestsCoalesce() {
        AtomicInteger flushes = new AtomicInteger();

        TreeRefresh.request();
        TreeRefresh.request();
        TreeRefresh.request();
        assertTrue(TreeRefresh.pending());

        TreeRefresh.flush(flushes::incrementAndGet);
        assertEquals(1, flushes.get(), "three requests, one reload and one broadcast");
        assertFalse(TreeRefresh.pending());

        TreeRefresh.flush(flushes::incrementAndGet);
        assertEquals(1, flushes.get(), "nothing was asked for, so nothing runs");
    }

    @Test
    @DisplayName("a request that arrives during the flush arms the next one")
    void aRequestDuringTheFlushIsNotLost() {
        AtomicInteger flushes = new AtomicInteger();

        TreeRefresh.request();
        TreeRefresh.flush(() -> {
            flushes.incrementAndGet();
            // The same tick's next packet, or another author's edit: it must owe a flush of its own.
            TreeRefresh.request();
        });

        assertTrue(TreeRefresh.pending(), "the edit that arrived during the refresh owes another");
        TreeRefresh.flush(flushes::incrementAndGet);
        assertEquals(2, flushes.get());
        assertFalse(TreeRefresh.pending());
    }
}
