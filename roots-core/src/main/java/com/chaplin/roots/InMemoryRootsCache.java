package com.chaplin.roots;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class InMemoryRootsCache implements RootsCache {
    private final Object lock = new Object();
    private final int maxEntries;
    private final LongSupplier ticker;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, PendingLoad> pendingLoads = new LinkedHashMap<>();
    private long generation;
    private long hits;
    private long misses;
    private long loads;
    private long coalescedLoads;
    private long evictions;
    private long expirations;
    private long invalidations;

    InMemoryRootsCache(int maxEntries) {
        this(maxEntries, System::nanoTime);
    }

    InMemoryRootsCache(int maxEntries, LongSupplier ticker) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Cache capacity must be positive");
        }
        this.maxEntries = maxEntries;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    @Override
    public <T> T get(String key, Class<T> type, CachePolicy policy, Supplier<? extends T> loader) {
        CacheValidation.key(key);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(loader, "loader");

        final PendingLoad pending;
        final boolean owner;
        synchronized (lock) {
            var now = ticker.getAsLong();
            var entry = entries.get(key);
            if (entry != null && !expired(entry, now)) {
                hits++;
                return cast(key, type, entry.value());
            }
            if (entry != null) {
                entries.remove(key);
                expirations++;
            }
            misses++;
            var existing = pendingLoads.get(key);
            if (existing == null) {
                pending = new PendingLoad(new CompletableFuture<>(), policy.tags(), generation);
                pendingLoads.put(key, pending);
                loads++;
                owner = true;
            } else {
                pending = existing;
                coalescedLoads++;
                owner = false;
            }
        }

        if (!owner) {
            return cast(key, type, await(pending.result()));
        }

        try {
            var value = Objects.requireNonNull(loader.get(), "Cache loaders must return a value");
            var typed = cast(key, type, value);
            synchronized (lock) {
                pendingLoads.remove(key, pending);
                if (pending.generation() == generation) {
                    removeExpired(ticker.getAsLong());
                    evictForInsert();
                    entries.put(key, new Entry(typed, deadline(policy.timeToLive()), policy.tags()));
                }
            }
            pending.result().complete(typed);
            return typed;
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                pendingLoads.remove(key, pending);
            }
            pending.result().completeExceptionally(failure);
            throw failure;
        }
    }

    @Override
    public boolean invalidate(String key) {
        CacheValidation.key(key);
        synchronized (lock) {
            var affected = entries.remove(key) != null;
            affected |= pendingLoads.remove(key) != null;
            if (affected) {
                generation++;
                invalidations++;
            }
            return affected;
        }
    }

    @Override
    public long invalidateTag(String tag) {
        CacheValidation.tag(tag);
        synchronized (lock) {
            long affected = 0;
            for (var iterator = entries.entrySet().iterator(); iterator.hasNext();) {
                if (iterator.next().getValue().tags().contains(tag)) {
                    iterator.remove();
                    affected++;
                }
            }
            for (var iterator = pendingLoads.entrySet().iterator(); iterator.hasNext();) {
                if (iterator.next().getValue().tags().contains(tag)) {
                    iterator.remove();
                    affected++;
                }
            }
            if (affected > 0) {
                generation++;
                invalidations += affected;
            }
            return affected;
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            var affected = (long) entries.size() + pendingLoads.size();
            entries.clear();
            pendingLoads.clear();
            if (affected > 0) {
                generation++;
                invalidations += affected;
            }
        }
    }

    @Override
    public CacheSnapshot snapshot() {
        synchronized (lock) {
            removeExpired(ticker.getAsLong());
            return new CacheSnapshot(
                    true,
                    entries.size(),
                    maxEntries,
                    hits,
                    misses,
                    loads,
                    coalescedLoads,
                    evictions,
                    expirations,
                    invalidations
            );
        }
    }

    private void removeExpired(long now) {
        for (var iterator = entries.values().iterator(); iterator.hasNext();) {
            if (expired(iterator.next(), now)) {
                iterator.remove();
                expirations++;
            }
        }
    }

    private void evictForInsert() {
        while (entries.size() >= maxEntries) {
            var iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
            evictions++;
        }
    }

    private long deadline(Duration timeToLive) {
        long duration;
        try {
            duration = timeToLive.toNanos();
        } catch (ArithmeticException ignored) {
            duration = Long.MAX_VALUE / 2;
        }
        duration = Math.min(duration, Long.MAX_VALUE / 2);
        return ticker.getAsLong() + duration;
    }

    private static boolean expired(Entry entry, long now) {
        return now - entry.expiresAt() >= 0;
    }

    private static <T> T cast(String key, Class<T> type, Object value) {
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Cache key '" + key + "' contains "
                    + value.getClass().getName() + " instead of " + type.getName());
        }
        return type.cast(value);
    }

    private static Object await(CompletableFuture<Object> result) {
        try {
            return result.join();
        } catch (CompletionException exception) {
            switch (exception.getCause()) {
                case RuntimeException failure -> throw failure;
                case Error failure -> throw failure;
                case Throwable failure -> throw new IllegalStateException("Cache load failed", failure);
            }
        }
    }

    private record Entry(Object value, long expiresAt, Set<String> tags) {
    }

    private record PendingLoad(CompletableFuture<Object> result, Set<String> tags, long generation) {
    }
}
