package com.chaplin.roots.load;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated test database only. Never point this harness at an application database. */
public final class BusinessStore {
    public static HikariDataSource pool;
    public static int rows;
    public static final Map<String, Runnable> UPDATES = new ConcurrentHashMap<>();
    public static LatencyHistogram checkout = new LatencyHistogram();
    public static LatencyHistogram transaction = new LatencyHistogram();
    public record Account(int id, int version, String status) {}

    public static List<Account> read(String user) {
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement(
                "SELECT account_id,version,status FROM roots_load_account WHERE user_id=? ORDER BY account_id")) {
            statement.setString(1, user);
            var accounts = new ArrayList<Account>();
            try (var result = statement.executeQuery()) {
                while (result.next()) accounts.add(new Account(result.getInt(1), result.getInt(2), result.getString(3)));
            }
            return accounts;
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }

    public static Account update(String user, int id, int expected, String status) {
        long start = System.nanoTime();
        try (var connection = pool.getConnection()) {
            checkout.add(System.nanoTime() - start);
            long began = System.nanoTime();
            connection.setAutoCommit(false);
            try (var update = connection.prepareStatement(
                    "UPDATE roots_load_account SET version=version+1,status=? WHERE user_id=? AND account_id=? AND version=?")) {
                update.setString(1, status); update.setString(2, user); update.setInt(3, id); update.setInt(4, expected);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Optimistic write conflict");
                try (var query = connection.prepareStatement("SELECT version,status FROM roots_load_account WHERE user_id=? AND account_id=?")) {
                    query.setString(1, user); query.setInt(2, id);
                    try (var result = query.executeQuery()) {
                        if (!result.next()) throw new IllegalStateException("Account disappeared");
                        var account = new Account(id, result.getInt(1), result.getString(2));
                        connection.commit();
                        transaction.add(System.nanoTime() - began);
                        return account;
                    }
                }
            } catch (Throwable failure) { connection.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }
}
