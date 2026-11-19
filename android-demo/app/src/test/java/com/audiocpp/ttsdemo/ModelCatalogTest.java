package com.audiocpp.ttsdemo;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.audiocpp.ttsdemo.ModelRepository.Source.HF_MIRROR;
import static com.audiocpp.ttsdemo.ModelRepository.Source.MODELSCOPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ModelCatalogTest {
    private static final List<ModelRepository.Entry> ENTRIES = List.of(
            new ModelRepository.Entry("family/model.gguf", 128, ""));

    @Test
    public void mirrorLimitUsesModelScopeAndRespectsCooldown() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        int[] calls = new int[ModelRepository.Source.values().length];
        ModelCatalog catalog = new ModelCatalog(source -> {
            calls[source.ordinal()]++;
            if (source == HF_MIRROR && calls[source.ordinal()] == 1) throw new ModelRepository.RateLimited(90);
            return ENTRIES;
        }, now::get);

        assertEquals(MODELSCOPE, catalog.list(HF_MIRROR).origin());
        assertEquals(ENTRIES, catalog.list(HF_MIRROR).entries());
        assertEquals(1, calls[HF_MIRROR.ordinal()]);
        assertEquals(1, calls[MODELSCOPE.ordinal()]);
        now.addAndGet(90_000);
        assertEquals(HF_MIRROR, catalog.list(HF_MIRROR).origin());
        assertEquals(2, calls[HF_MIRROR.ordinal()]);
    }

    @Test
    public void existingMirrorListSkipsFallbackOnLimit() throws Exception {
        AtomicLong now = new AtomicLong(1000);
        ModelCatalog catalog = new ModelCatalog(source -> {
            assertEquals(HF_MIRROR, source);
            if (now.get() > 1000) throw new ModelRepository.RateLimited(30);
            return ENTRIES;
        }, now::get);

        assertFalse(catalog.list(HF_MIRROR).stale());
        now.addAndGet(60_000);
        assertFalse(catalog.list(HF_MIRROR).stale());
        now.addAndGet(4 * 60_000);
        ModelCatalog.Listing listing = catalog.list(HF_MIRROR);
        assertTrue(listing.stale());
        assertEquals(HF_MIRROR, listing.origin());
        assertEquals(ENTRIES, listing.entries());
    }

    @Test
    public void fallbackFailurePreservesRateLimitAndRemainingWait() {
        AtomicLong now = new AtomicLong(1000);
        ModelCatalog catalog = new ModelCatalog(source -> {
            if (source == HF_MIRROR) throw new ModelRepository.RateLimited(90);
            throw new IOException("unavailable");
        }, now::get);

        assertEquals(90, assertThrows(ModelRepository.RateLimited.class, () -> catalog.list(HF_MIRROR)).retrySeconds);
        now.addAndGet(12_000);
        assertEquals(78, assertThrows(ModelRepository.RateLimited.class, () -> catalog.list(HF_MIRROR)).retrySeconds);
    }

    @Test
    public void parsesServerRetryHints() {
        assertEquals(23, ModelRepository.retryDelaySeconds(" 23 ", "\"api\";r=0;t=130", 1000));
        assertEquals(119, ModelRepository.retryDelaySeconds("Thu, 01 Jan 1970 00:02:00 GMT", null, 1000));
        assertEquals(130, ModelRepository.retryDelaySeconds(null, "\"api\";r=0;t=130", 1000));
        assertEquals(60, ModelRepository.retryDelaySeconds("unknown", null, 1000));
    }
}
