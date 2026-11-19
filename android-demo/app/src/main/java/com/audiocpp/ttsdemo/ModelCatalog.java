package com.audiocpp.ttsdemo;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;

final class ModelCatalog {
    private static final long FRESH_MS = 5 * 60_000;
    private static final long MAX_STALE_MS = 24 * 60 * 60_000;

    record Listing(ModelRepository.Source origin, List<ModelRepository.Entry> entries, boolean stale) {}

    interface Fetcher {
        List<ModelRepository.Entry> fetch(ModelRepository.Source source) throws IOException, JSONException;
    }

    private static final class State {
        List<ModelRepository.Entry> entries;
        long fetchedAt;
        long retryAt;
    }

    private final Map<ModelRepository.Source, State> states = new EnumMap<>(ModelRepository.Source.class);
    private final Fetcher fetcher;
    private final LongSupplier clock;

    ModelCatalog(Fetcher fetcher, LongSupplier clock) {
        this.fetcher = fetcher;
        this.clock = clock;
        for (ModelRepository.Source source : ModelRepository.Source.values()) states.put(source, new State());
    }

    Listing list(ModelRepository.Source selected) throws IOException, JSONException {
        try {
            return request(selected);
        } catch (ModelRepository.RateLimited limit) {
            if (selected == ModelRepository.Source.HF_MIRROR) {
                // Only borrow metadata; the download still uses the selected mirror and verifies the weight.
                try {
                    return request(ModelRepository.Source.MODELSCOPE);
                } catch (IOException | JSONException fallbackFailure) {
                    limit.addSuppressed(fallbackFailure);
                }
            }
            throw limit;
        }
    }

    private Listing request(ModelRepository.Source source) throws IOException, JSONException {
        State state = states.get(source);
        synchronized (state) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            long now = clock.getAsLong();
            long age = now - state.fetchedAt;
            if (state.entries != null && age < FRESH_MS) return new Listing(source, state.entries, false);
            if (state.retryAt > now) {
                if (state.entries != null && age < MAX_STALE_MS) return new Listing(source, state.entries, true);
                throw new ModelRepository.RateLimited((state.retryAt - now + 999) / 1000);
            }
            try {
                state.entries = Collections.unmodifiableList(new ArrayList<>(fetcher.fetch(source)));
                state.fetchedAt = clock.getAsLong();
                state.retryAt = 0;
                return new Listing(source, state.entries, false);
            } catch (ModelRepository.RateLimited limit) {
                state.retryAt = clock.getAsLong() + limit.retrySeconds * 1000;
                if (state.entries != null && age < MAX_STALE_MS) return new Listing(source, state.entries, true);
                throw limit;
            }
        }
    }
}
