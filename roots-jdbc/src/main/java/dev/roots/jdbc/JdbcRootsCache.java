package dev.roots.jdbc;

import dev.roots.CachePolicy;
import dev.roots.CacheSnapshot;
import dev.roots.RootsCache;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A bounded, distributed JDBC {@link RootsCache}.
 *
 * <p>Entries, tags, invalidations, and miss ownership are shared by every
 * application node using the same tables. A short database lease recovers a
 * load abandoned by a failed process. Publication is generation-checked, so
 * an invalidation that overlaps a loader can never publish the stale result.
 * The adapter owns neither the supplied data source nor its connection pool.</p>
 *
 * <p>Diagnostic counters are local to this adapter instance while the entry
 * count is read from the shared table. All nodes sharing a table should use the
 * same capacity and codec.</p>
 */
public final class JdbcRootsCache implements RootsCache {
    /** Default unqualified cache table name. */
    public static final String DEFAULT_TABLE = "roots_cache";
    /** Maximum encoded value length accepted by the bundled schema. */
    public static final int MAX_ENCODED_VALUE_LENGTH = 8192;
    /** Default abandoned-loader recovery window. */
    public static final Duration DEFAULT_LOAD_LEASE = Duration.ofMinutes(5);
    /** Default delay between observations of another node's load. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(10);

    private static final int MAX_KEY_LENGTH = 1024;
    private static final int MAX_TAG_LENGTH = 256;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");

    private final DataSource dataSource;
    private final JdbcCacheValueCodec codec;
    private final String table;
    private final String tagsTable;
    private final String loadsTable;
    private final String loadTagsTable;
    private final String metadataTable;
    private final int maxEntries;
    private final long loadLeaseMillis;
    private final long pollNanos;
    private final LongSupplier clock;
    private final LongConsumer sleeper;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong coalescedLoads = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();
    private final AtomicLong invalidations = new AtomicLong();

    /** Creates a cache with the default table, scalar codec, and capacity.
     * @param dataSource externally managed JDBC data source
     */
    public JdbcRootsCache(DataSource dataSource) {
        this(dataSource, JdbcCacheValueCodec.standard());
    }

    /** Creates a cache with the default table and capacity.
     * @param dataSource externally managed JDBC data source
     * @param codec thread-safe application value codec
     */
    public JdbcRootsCache(DataSource dataSource, JdbcCacheValueCodec codec) {
        this(dataSource, codec, DEFAULT_TABLE);
    }

    /** Creates a cache with a custom unqualified base table name.
     * @param dataSource externally managed JDBC data source
     * @param codec thread-safe application value codec
     * @param table base table; tag, load, and metadata names are derived from it
     */
    public JdbcRootsCache(DataSource dataSource, JdbcCacheValueCodec codec, String table) {
        this(dataSource, codec, table, DEFAULT_MAX_ENTRIES, DEFAULT_LOAD_LEASE, DEFAULT_POLL_INTERVAL);
    }

    /** Creates a fully configured distributed cache.
     * @param dataSource externally managed JDBC data source
     * @param codec thread-safe application value codec
     * @param table unqualified base table name
     * @param maxEntries maximum shared entries retained after each publication
     * @param loadLease abandoned-loader recovery window
     * @param pollInterval delay while another node owns a miss
     */
    public JdbcRootsCache(
            DataSource dataSource,
            JdbcCacheValueCodec codec,
            String table,
            int maxEntries,
            Duration loadLease,
            Duration pollInterval
    ) {
        this(dataSource, codec, table, maxEntries, loadLease, pollInterval,
                System::currentTimeMillis, LockSupport::parkNanos);
    }

    JdbcRootsCache(
            DataSource dataSource,
            JdbcCacheValueCodec codec,
            String table,
            int maxEntries,
            Duration loadLease,
            Duration pollInterval,
            LongSupplier clock,
            LongConsumer sleeper
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.table = validateTable(table);
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Cache capacity must be positive");
        }
        this.maxEntries = maxEntries;
        loadLeaseMillis = positiveMillis(loadLease, "Load lease");
        pollNanos = positiveNanos(pollInterval, "Poll interval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        tagsTable = this.table + "_tags";
        loadsTable = this.table + "_loads";
        loadTagsTable = this.table + "_load_tags";
        metadataTable = this.table + "_meta";
    }

    /** Returns portable schema statements for the default tables.
     * @return immutable ordered schema statements
     */
    public static List<String> schemaStatements() {
        return schemaStatements(DEFAULT_TABLE);
    }

    /** Returns portable schema statements for a custom base table.
     * @param table unquoted SQL base identifier
     * @return immutable ordered schema statements
     */
    public static List<String> schemaStatements(String table) {
        var base = validateTable(table);
        var tags = base + "_tags";
        var loads = base + "_loads";
        var loadTags = base + "_load_tags";
        var metadata = base + "_meta";
        return List.of(
                "CREATE TABLE " + base + " ("
                        + "cache_key VARCHAR(1024) PRIMARY KEY, "
                        + "encoded_value VARCHAR(8192) NOT NULL, "
                        + "expires_at_epoch_ms BIGINT NOT NULL, "
                        + "stored_at_epoch_ms BIGINT NOT NULL)",
                "CREATE INDEX " + base + "_expiry_idx ON " + base + " (expires_at_epoch_ms)",
                "CREATE TABLE " + tags + " ("
                        + "cache_tag VARCHAR(256) NOT NULL, "
                        + "cache_key VARCHAR(1024) NOT NULL, "
                        + "PRIMARY KEY (cache_tag, cache_key), "
                        + "FOREIGN KEY (cache_key) REFERENCES " + base + " (cache_key) ON DELETE CASCADE)",
                "CREATE TABLE " + loads + " ("
                        + "cache_key VARCHAR(1024) PRIMARY KEY, "
                        + "owner_token VARCHAR(64) NOT NULL, "
                        + "cache_generation BIGINT NOT NULL, "
                        + "expires_at_epoch_ms BIGINT NOT NULL)",
                "CREATE INDEX " + loads + "_expiry_idx ON " + loads + " (expires_at_epoch_ms)",
                "CREATE TABLE " + loadTags + " ("
                        + "cache_tag VARCHAR(256) NOT NULL, "
                        + "cache_key VARCHAR(1024) NOT NULL, "
                        + "PRIMARY KEY (cache_tag, cache_key), "
                        + "FOREIGN KEY (cache_key) REFERENCES " + loads + " (cache_key) ON DELETE CASCADE)",
                "CREATE TABLE " + metadata + " (lock_id INTEGER PRIMARY KEY, cache_generation BIGINT NOT NULL)",
                "INSERT INTO " + metadata + " (lock_id, cache_generation) VALUES (1, 0)"
        );
    }

    /** Creates the default schema once for tests or first-run tooling.
     * @param dataSource target data source
     */
    public static void createSchema(DataSource dataSource) {
        createSchema(dataSource, DEFAULT_TABLE);
    }

    /** Creates a custom schema once for tests or first-run tooling.
     * @param dataSource target data source
     * @param table unquoted SQL base identifier
     */
    public static void createSchema(DataSource dataSource, String table) {
        Objects.requireNonNull(dataSource, "dataSource");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (var sql : schemaStatements(table)) {
                statement.execute(sql);
            }
        } catch (SQLException exception) {
            throw failure("create schema", exception);
        }
    }

    @Override
    public <T> T get(String key, Class<T> type, CachePolicy policy, Supplier<? extends T> loader) {
        validateKey(key);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(loader, "loader");
        policy.tags().forEach(JdbcRootsCache::validateTag);

        var cached = readEntry(key, type, now());
        if (cached.isPresent()) {
            hits.incrementAndGet();
            return cached.get();
        }
        misses.incrementAndGet();

        var joined = false;
        while (true) {
            var claim = claim(key, policy.tags());
            if (claim.owner()) {
                loads.incrementAndGet();
                return loadAndPublish(key, type, policy, loader, claim);
            }
            if (!joined) {
                coalescedLoads.incrementAndGet();
                joined = true;
            }
            var shared = readEntry(key, type, now());
            if (shared.isPresent()) {
                return shared.get();
            }
            if (!loadExists(key, now())) {
                continue;
            }
            pause();
        }
    }

    @Override
    public boolean invalidate(String key) {
        validateKey(key);
        try (var connection = dataSource.getConnection()) {
            var autoCommit = begin(connection);
            try {
                lockGeneration(connection);
                var affected = exists(connection, table, key) || exists(connection, loadsTable, key);
                deleteKey(connection, table, key);
                deleteKey(connection, loadsTable, key);
                if (affected) {
                    incrementGeneration(connection);
                    invalidations.incrementAndGet();
                }
                connection.commit();
                return affected;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw translated("invalidate key", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            throw failure("invalidate key", exception);
        }
    }

    @Override
    public long invalidateTag(String tag) {
        validateTag(tag);
        try (var connection = dataSource.getConnection()) {
            var autoCommit = begin(connection);
            try {
                lockGeneration(connection);
                var keys = taggedKeys(connection, tag);
                for (var key : keys) {
                    deleteKey(connection, table, key);
                    deleteKey(connection, loadsTable, key);
                }
                if (!keys.isEmpty()) {
                    incrementGeneration(connection);
                    invalidations.addAndGet(keys.size());
                }
                connection.commit();
                return keys.size();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw translated("invalidate tag", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            throw failure("invalidate tag", exception);
        }
    }

    @Override
    public void clear() {
        try (var connection = dataSource.getConnection()) {
            var autoCommit = begin(connection);
            try {
                lockGeneration(connection);
                var keys = allKeys(connection);
                executeDelete(connection, "DELETE FROM " + table);
                executeDelete(connection, "DELETE FROM " + loadsTable);
                if (!keys.isEmpty()) {
                    incrementGeneration(connection);
                    invalidations.addAndGet(keys.size());
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw translated("clear cache", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            throw failure("clear cache", exception);
        }
    }

    @Override
    public CacheSnapshot snapshot() {
        var entries = purgeAndCount();
        return new CacheSnapshot(true, entries, maxEntries, hits.get(), misses.get(), loads.get(),
                coalescedLoads.get(), evictions.get(), expirations.get(), invalidations.get());
    }

    private <T> T loadAndPublish(
            String key,
            Class<T> type,
            CachePolicy policy,
            Supplier<? extends T> loader,
            Claim claim
    ) {
        try {
            var value = Objects.requireNonNull(loader.get(), "Cache loaders must return a value");
            if (!type.isInstance(value)) {
                throw new IllegalStateException("Cache loader for key '" + key + "' returned "
                        + value.getClass().getName() + " instead of " + type.getName());
            }
            var encoded = Objects.requireNonNull(codec.encode(value), "Cache value codec result");
            if (encoded.length() > MAX_ENCODED_VALUE_LENGTH) {
                throw new IllegalArgumentException("Encoded JDBC cache value exceeds "
                        + MAX_ENCODED_VALUE_LENGTH + " characters");
            }
            publish(key, encoded, policy, claim);
            return type.cast(value);
        } catch (RuntimeException | Error failure) {
            try {
                abandon(key, claim.ownerToken());
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private Claim claim(String key, Set<String> tags) {
        var ownerToken = UUID.randomUUID().toString();
        var current = now();
        try (var connection = dataSource.getConnection()) {
            var autoCommit = begin(connection);
            var expiredEntry = false;
            try {
                var generation = lockGeneration(connection);
                var entryExpiry = entryExpiry(connection, key);
                if (entryExpiry.isPresent()) {
                    if (entryExpiry.get() > current) {
                        connection.commit();
                        return Claim.observer();
                    }
                    deleteKey(connection, table, key);
                    expiredEntry = true;
                }
                deleteExpiredLoad(connection, key, current);
                if (exists(connection, loadsTable, key)) {
                    connection.commit();
                    if (expiredEntry) {
                        expirations.incrementAndGet();
                    }
                    return Claim.observer();
                }
                var sql = "INSERT INTO " + loadsTable
                        + " (cache_key, owner_token, cache_generation, expires_at_epoch_ms) VALUES (?, ?, ?, ?)";
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setString(1, key);
                    statement.setString(2, ownerToken);
                    statement.setLong(3, generation);
                    statement.setLong(4, deadline(current, loadLeaseMillis));
                    exactlyOne(statement.executeUpdate(), "claim cache load");
                }
                insertTags(connection, loadTagsTable, key, tags);
                connection.commit();
                if (expiredEntry) {
                    expirations.incrementAndGet();
                }
                return new Claim(true, ownerToken, generation);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw translated("claim cache load", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            throw failure("claim cache load", exception);
        }
    }

    private void publish(String key, String encoded, CachePolicy policy, Claim claim) {
        var current = now();
        var expires = deadline(current, positiveMillis(policy.timeToLive(), "Cache time-to-live"));
        try (var connection = dataSource.getConnection()) {
            var autoCommit = begin(connection);
            try {
                var generation = lockGeneration(connection);
                if (generation == claim.generation() && ownsLoad(connection, key, claim.ownerToken())) {
                    upsertEntry(connection, key, encoded, expires, current);
                    deleteRowsByKey(connection, tagsTable, key);
                    insertTags(connection, tagsTable, key, policy.tags());
                    evictOverflow(connection, key);
                }
                deleteOwnedLoad(connection, key, claim.ownerToken());
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw translated("publish cache value", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            throw failure("publish cache value", exception);
        }
    }

    private <T> Optional<T> readEntry(String key, Class<T> type, long current) {
        var sql = "SELECT encoded_value, expires_at_epoch_ms FROM " + table + " WHERE cache_key = ?";
        final StoredEntry stored;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                stored = new StoredEntry(result.getString(1), result.getLong(2));
                if (result.next()) {
                    throw new JdbcCacheException("JDBC cache lookup returned duplicate rows");
                }
            }
        } catch (SQLException exception) {
            throw failure("read cache value", exception);
        }
        if (stored.expiresAt() <= current) {
            expireEntry(key, stored.expiresAt(), current);
            return Optional.empty();
        }
        try {
            return Optional.of(Objects.requireNonNull(codec.decode(stored.encoded(), type),
                    "Decoded JDBC cache value"));
        } catch (IllegalStateException exception) {
            throw new IllegalStateException("Cache key '" + key + "' cannot be decoded as "
                    + type.getName(), exception);
        } catch (RuntimeException exception) {
            throw new JdbcCacheException("Could not decode JDBC cache key '" + key + "'", exception);
        }
    }

    private void expireEntry(String key, long observedExpiry, long current) {
        var sql = "DELETE FROM " + table
                + " WHERE cache_key = ? AND expires_at_epoch_ms = ? AND expires_at_epoch_ms <= ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setLong(2, observedExpiry);
            statement.setLong(3, current);
            var changed = validCount(statement.executeUpdate(), "expire cache value");
            if (changed == 1) {
                expirations.incrementAndGet();
            }
        } catch (SQLException exception) {
            throw failure("expire cache value", exception);
        }
    }

    private boolean loadExists(String key, long current) {
        var sql = "SELECT expires_at_epoch_ms FROM " + loadsTable + " WHERE cache_key = ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                var expires = result.getLong(1);
                if (result.next()) {
                    throw new JdbcCacheException("JDBC cache load lookup returned duplicate rows");
                }
                return expires > current;
            }
        } catch (SQLException exception) {
            throw failure("observe cache load", exception);
        }
    }

    private void abandon(String key, String ownerToken) {
        try (var connection = dataSource.getConnection()) {
            var autoCommit = begin(connection);
            try {
                lockGeneration(connection);
                deleteOwnedLoad(connection, key, ownerToken);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw translated("abandon cache load", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            throw failure("abandon cache load", exception);
        }
    }

    private int purgeAndCount() {
        var current = now();
        try (var connection = dataSource.getConnection()) {
            var autoCommit = begin(connection);
            try {
                lockGeneration(connection);
                var expired = executeDelete(connection,
                        "DELETE FROM " + table + " WHERE expires_at_epoch_ms <= ?", current);
                executeDelete(connection,
                        "DELETE FROM " + loadsTable + " WHERE expires_at_epoch_ms <= ?", current);
                var count = count(connection);
                connection.commit();
                expirations.addAndGet(expired);
                if (count > Integer.MAX_VALUE) {
                    throw new JdbcCacheException("JDBC cache count exceeds the supported integer range");
                }
                return (int) count;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw translated("snapshot cache", exception);
            } finally {
                restoreAutoCommit(connection, autoCommit);
            }
        } catch (SQLException exception) {
            throw failure("snapshot cache", exception);
        }
    }

    private long lockGeneration(Connection connection) throws SQLException {
        var sql = "SELECT cache_generation FROM " + metadataTable + " WHERE lock_id = 1 FOR UPDATE";
        try (var statement = connection.prepareStatement(sql);
             var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new JdbcCacheException("JDBC cache metadata row is missing");
            }
            var generation = result.getLong(1);
            if (generation < 0 || result.next()) {
                throw new JdbcCacheException("JDBC cache metadata row is corrupt");
            }
            return generation;
        }
    }

    private void incrementGeneration(Connection connection) throws SQLException {
        var sql = "UPDATE " + metadataTable
                + " SET cache_generation = cache_generation + 1 WHERE lock_id = 1";
        try (var statement = connection.prepareStatement(sql)) {
            exactlyOne(statement.executeUpdate(), "advance cache generation");
        }
    }

    private void upsertEntry(
            Connection connection,
            String key,
            String encoded,
            long expires,
            long storedAt
    ) throws SQLException {
        var update = "UPDATE " + table
                + " SET encoded_value = ?, expires_at_epoch_ms = ?, stored_at_epoch_ms = ? WHERE cache_key = ?";
        try (var statement = connection.prepareStatement(update)) {
            statement.setString(1, encoded);
            statement.setLong(2, expires);
            statement.setLong(3, storedAt);
            statement.setString(4, key);
            if (validCount(statement.executeUpdate(), "update cache value") == 1) {
                return;
            }
        }
        var insert = "INSERT INTO " + table
                + " (cache_key, encoded_value, expires_at_epoch_ms, stored_at_epoch_ms) VALUES (?, ?, ?, ?)";
        try (var statement = connection.prepareStatement(insert)) {
            statement.setString(1, key);
            statement.setString(2, encoded);
            statement.setLong(3, expires);
            statement.setLong(4, storedAt);
            exactlyOne(statement.executeUpdate(), "insert cache value");
        }
    }

    private void evictOverflow(Connection connection, String publishedKey) throws SQLException {
        var overflow = count(connection) - maxEntries;
        if (overflow <= 0) {
            return;
        }
        var victims = new ArrayList<String>();
        var sql = "SELECT cache_key FROM " + table
                + " WHERE cache_key <> ? ORDER BY stored_at_epoch_ms, cache_key";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, publishedKey);
            try (var result = statement.executeQuery()) {
                while (victims.size() < overflow && result.next()) {
                    victims.add(result.getString(1));
                }
            }
        }
        for (var victim : victims) {
            deleteKey(connection, table, victim);
        }
        evictions.addAndGet(victims.size());
    }

    private long count(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
             var result = statement.executeQuery()) {
            if (!result.next()) {
                throw new JdbcCacheException("JDBC cache count returned no row");
            }
            var count = result.getLong(1);
            if (count < 0 || result.next()) {
                throw new JdbcCacheException("JDBC cache count returned an invalid result");
            }
            return count;
        }
    }

    private Set<String> taggedKeys(Connection connection, String tag) throws SQLException {
        var keys = new LinkedHashSet<String>();
        collectTaggedKeys(connection, tagsTable, tag, keys);
        collectTaggedKeys(connection, loadTagsTable, tag, keys);
        return keys;
    }

    private void collectTaggedKeys(
            Connection connection,
            String source,
            String tag,
            Set<String> keys
    ) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT cache_key FROM " + source + " WHERE cache_tag = ?")) {
            statement.setString(1, tag);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    keys.add(result.getString(1));
                }
            }
        }
    }

    private Set<String> allKeys(Connection connection) throws SQLException {
        var keys = new LinkedHashSet<String>();
        collectKeys(connection, table, keys);
        collectKeys(connection, loadsTable, keys);
        return keys;
    }

    private static void collectKeys(Connection connection, String source, Set<String> keys) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT cache_key FROM " + source);
             var result = statement.executeQuery()) {
            while (result.next()) {
                keys.add(result.getString(1));
            }
        }
    }

    private void insertTags(Connection connection, String destination, String key, Set<String> tags)
            throws SQLException {
        var sql = "INSERT INTO " + destination + " (cache_tag, cache_key) VALUES (?, ?)";
        for (var tag : tags) {
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, tag);
                statement.setString(2, key);
                exactlyOne(statement.executeUpdate(), "insert cache tag");
            }
        }
    }

    private boolean ownsLoad(Connection connection, String key, String ownerToken) throws SQLException {
        var sql = "SELECT owner_token FROM " + loadsTable + " WHERE cache_key = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                var matches = ownerToken.equals(result.getString(1));
                if (result.next()) {
                    throw new JdbcCacheException("JDBC cache load owner lookup returned duplicate rows");
                }
                return matches;
            }
        }
    }

    private void deleteExpiredLoad(Connection connection, String key, long current) throws SQLException {
        var sql = "DELETE FROM " + loadsTable + " WHERE cache_key = ? AND expires_at_epoch_ms <= ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setLong(2, current);
            validCount(statement.executeUpdate(), "delete expired cache load");
        }
    }

    private void deleteOwnedLoad(Connection connection, String key, String ownerToken) throws SQLException {
        var sql = "DELETE FROM " + loadsTable + " WHERE cache_key = ? AND owner_token = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, ownerToken);
            validCount(statement.executeUpdate(), "delete cache load");
        }
    }

    private static boolean exists(Connection connection, String source, String key) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT cache_key FROM " + source + " WHERE cache_key = ?")) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                if (result.next()) {
                    throw new JdbcCacheException("JDBC cache key lookup returned duplicate rows");
                }
                return true;
            }
        }
    }

    private Optional<Long> entryExpiry(Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT expires_at_epoch_ms FROM " + table + " WHERE cache_key = ?")) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                var expiry = result.getLong(1);
                if (result.next()) {
                    throw new JdbcCacheException("JDBC cache expiry lookup returned duplicate rows");
                }
                return Optional.of(expiry);
            }
        }
    }

    private static void deleteKey(Connection connection, String source, String key) throws SQLException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM " + source + " WHERE cache_key = ?")) {
            statement.setString(1, key);
            validCount(statement.executeUpdate(), "delete cache key");
        }
    }

    private static void deleteRowsByKey(Connection connection, String source, String key) throws SQLException {
        try (var statement = connection.prepareStatement(
                "DELETE FROM " + source + " WHERE cache_key = ?")) {
            statement.setString(1, key);
            nonNegative(statement.executeUpdate(), "delete cache rows by key");
        }
    }

    private static int executeDelete(Connection connection, String sql, long parameter) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            return nonNegative(statement.executeUpdate(), "purge cache rows");
        }
    }

    private static int executeDelete(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            return nonNegative(statement.executeUpdate(), "delete cache rows");
        }
    }

    private void pause() {
        if (Thread.currentThread().isInterrupted()) {
            throw new JdbcCacheException("Interrupted while waiting for a JDBC cache load");
        }
        sleeper.accept(pollNanos);
        if (Thread.currentThread().isInterrupted()) {
            throw new JdbcCacheException("Interrupted while waiting for a JDBC cache load");
        }
    }

    private long now() {
        var value = clock.getAsLong();
        if (value < 0) {
            throw new IllegalStateException("JDBC cache clock must return a non-negative epoch millisecond");
        }
        return value;
    }

    private static long deadline(long current, long durationMillis) {
        if (durationMillis > Long.MAX_VALUE - current) {
            return Long.MAX_VALUE;
        }
        return current + durationMillis;
    }

    private static long positiveMillis(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            return Math.max(1, duration.toMillis());
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long positiveNanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            return Math.max(1, duration.toNanos());
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static String validateTable(String table) {
        Objects.requireNonNull(table, "table");
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("JDBC cache table must be an unqualified SQL identifier of at most 40 characters");
        }
        return table;
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH || controls(key)) {
            throw new IllegalArgumentException("Cache keys must be non-blank, control-free, and at most 1024 characters");
        }
    }

    private static void validateTag(String tag) {
        if (tag == null || tag.isBlank() || tag.length() > MAX_TAG_LENGTH || controls(tag)) {
            throw new IllegalArgumentException("Cache tags must be non-blank, control-free, and at most 256 characters");
        }
    }

    private static boolean controls(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static boolean begin(Connection connection) throws SQLException {
        var autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        return autoCommit;
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            throw failure("restore connection auto-commit", exception);
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void exactlyOne(int changed, String operation) {
        if (changed != 1) {
            throw new JdbcCacheException("JDBC " + operation + " did not affect exactly one row");
        }
    }

    private static int validCount(int changed, String operation) {
        if (changed < 0 || changed > 1) {
            throw new JdbcCacheException("JDBC " + operation + " returned an invalid row count");
        }
        return changed;
    }

    private static int nonNegative(int changed, String operation) {
        if (changed < 0) {
            throw new JdbcCacheException("JDBC " + operation + " returned an invalid row count");
        }
        return changed;
    }

    private static RuntimeException translated(String operation, Throwable exception) {
        return exception instanceof JdbcCacheException cacheFailure
                ? cacheFailure : failure(operation, exception);
    }

    private static JdbcCacheException failure(String operation, Throwable cause) {
        return new JdbcCacheException("Could not " + operation + " in JDBC cache", cause);
    }

    private record Claim(boolean owner, String ownerToken, long generation) {
        private static Claim observer() {
            return new Claim(false, "", -1);
        }
    }

    private record StoredEntry(String encoded, long expiresAt) {
    }
}
