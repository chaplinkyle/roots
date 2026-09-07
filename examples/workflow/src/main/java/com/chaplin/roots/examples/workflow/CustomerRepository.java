package com.chaplin.roots.examples.workflow;

import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.ValidationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Application-owned SQL. Each call borrows a connection; no live view retains one. */
public final class CustomerRepository {
    public record Fields(String company, String contact, String email) {
        public Fields {
            bounded("company", company, 120);
            bounded("contact", contact, 120);
            bounded("email", email, 254);
        }
        public Fields validated() {
            var value = new Fields(company.strip(), contact.strip(), email.strip());
            if (value.company.isBlank()) throw ValidationException.field("company", "Enter a company name.");
            if (value.contact.isBlank()) throw ValidationException.field("contact", "Enter a contact name.");
            // Deliberately modest syntax check; deliverability belongs to a confirmation workflow.
            if (!value.email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                throw ValidationException.field("email", "Enter an email address such as name@example.com.");
            return value;
        }
        private static void bounded(String field, String value, int maximum) {
            if (value == null || value.length() > maximum || value.chars().anyMatch(Character::isISOControl))
                throw ValidationException.field(field, "Use at most " + maximum + " characters without control characters.");
        }
    }
    public record Customer(UUID id, Fields fields, long version, long createdAt) { }
    public record Draft(UUID id, String owner, UUID customerId, long baseVersion, Fields fields,
                        long version, boolean completed, long updatedAt) { }
    public record Page(List<Customer> items, String next) { }
    public static final class Conflict extends RuntimeException {
        Conflict(String message) { super(message); }
    }
    public static final class Missing extends RuntimeException {
        Missing() { super("This record is unavailable."); }
    }
    private final DataSource dataSource;
    public CustomerRepository(DataSource dataSource) { this.dataSource = dataSource; }

    public Page customers(AuthenticatedIdentity actor, String prefix, String cursor) {
        read(actor);
        if (prefix == null || prefix.length() > 120) throw new IllegalArgumentException("Search is limited to 120 characters");
        long beforeTime = Long.MAX_VALUE;
        String beforeId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        if (cursor != null && !cursor.isEmpty()) {
            if (cursor.length() > 58 || !cursor.contains(":")) throw new IllegalArgumentException("Invalid page cursor");
            var split = cursor.split(":", -1);
            if (split.length != 2) throw new IllegalArgumentException("Invalid page cursor");
            beforeTime = Long.parseLong(split[0]);
            beforeId = id(split[1]).toString();
            if (beforeTime < 0) throw new IllegalArgumentException("Invalid page cursor");
        }
        try (var connection = dataSource.getConnection(); var query = statement(connection,
                "SELECT * FROM workflow_customers WHERE company_search LIKE ? ESCAPE '!' "
                        + "AND (created_at < ? OR (created_at = ? AND customer_id < ?)) "
                        + "ORDER BY created_at DESC, customer_id DESC LIMIT 26")) {
            query.setString(1, prefix.strip().toLowerCase(Locale.ROOT).replace("!", "!!")
                    .replace("%", "!%").replace("_", "!_") + "%");
            query.setLong(2, beforeTime); query.setLong(3, beforeTime); query.setString(4, beforeId);
            var rows = new ArrayList<Customer>();
            try (var result = query.executeQuery()) { while (result.next()) rows.add(customer(result)); }
            String next = null;
            if (rows.size() > 25) {
                rows.removeLast();
                var last = rows.getLast();
                next = last.createdAt() + ":" + last.id();
            }
            return new Page(List.copyOf(rows), next);
        } catch (SQLException failure) { throw databaseFailure(failure); }
    }

    public Customer customer(AuthenticatedIdentity actor, UUID id) {
        read(actor);
        try (var connection = dataSource.getConnection()) { return customer(connection, id); }
        catch (SQLException failure) { throw databaseFailure(failure); }
    }

    public List<Draft> drafts(AuthenticatedIdentity actor) {
        edit(actor);
        try (var connection = dataSource.getConnection(); var query = statement(connection,
                "SELECT * FROM workflow_drafts WHERE owner_name = ? AND completed = FALSE "
                        + "ORDER BY updated_at DESC, draft_id DESC LIMIT 25")) {
            query.setString(1, actor.name());
            var rows = new ArrayList<Draft>();
            try (var result = query.executeQuery()) { while (result.next()) rows.add(draft(result)); }
            return List.copyOf(rows);
        } catch (SQLException failure) { throw databaseFailure(failure); }
    }

    public Draft draft(AuthenticatedIdentity actor, UUID id) {
        edit(actor);
        try (var connection = dataSource.getConnection()) { return draft(connection, actor, id, false); }
        catch (SQLException failure) { throw databaseFailure(failure); }
    }

    public Draft create(AuthenticatedIdentity actor, UUID customerId) {
        edit(actor);
        return transaction(connection -> {
            var customer = customerId == null ? null : customer(connection, customerId);
            var value = new Draft(UUID.randomUUID(), actor.name(), customerId, customer == null ? 0 : customer.version(),
                    customer == null ? new Fields("", "", "") : customer.fields(), 0, false, Instant.now().toEpochMilli());
            try (var insert = statement(connection, "INSERT INTO workflow_drafts "
                    + "(draft_id, owner_name, customer_id, base_version, company, contact_name, email, version, completed, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, FALSE, ?)")) {
                insert.setString(1, value.id().toString()); insert.setString(2, actor.name());
                insert.setString(3, customerId == null ? null : customerId.toString());
                insert.setLong(4, value.baseVersion()); fields(insert, 5, value.fields());
                insert.setLong(8, value.updatedAt()); insert.executeUpdate();
            }
            return value;
        });
    }

    public Draft save(AuthenticatedIdentity actor, UUID id, long expected, Fields fields) {
        edit(actor);
        return transaction(connection -> {
            var current = draft(connection, actor, id, true);
            currentVersion(current, expected);
            if (current.fields().equals(fields)) return current;
            try (var update = statement(connection, "UPDATE workflow_drafts SET company = ?, contact_name = ?, "
                    + "email = ?, version = version + 1, updated_at = ? WHERE draft_id = ? AND owner_name = ?")) {
                fields(update, 1, fields); update.setLong(4, Instant.now().toEpochMilli());
                update.setString(5, id.toString()); update.setString(6, actor.name()); update.executeUpdate();
            }
            return draft(connection, actor, id, false);
        });
    }

    /** Explicit user review: retain the draft fields but accept exactly the displayed customer version. */
    public Draft review(AuthenticatedIdentity actor, UUID id, long expectedDraft, long displayedCustomerVersion) {
        edit(actor);
        return transaction(connection -> {
            var current = draft(connection, actor, id, true);
            currentVersion(current, expectedDraft);
            if (current.customerId() == null) throw new Conflict("This draft has no saved customer to compare.");
            var customer = customer(connection, current.customerId());
            if (customer.version() != displayedCustomerVersion)
                throw new Conflict("The customer changed again. Review the latest saved values.");
            try (var update = statement(connection, "UPDATE workflow_drafts SET base_version = ?, version = version + 1, "
                    + "updated_at = ? WHERE draft_id = ? AND owner_name = ?")) {
                update.setLong(1, customer.version()); update.setLong(2, Instant.now().toEpochMilli());
                update.setString(3, id.toString()); update.setString(4, actor.name()); update.executeUpdate();
            }
            return draft(connection, actor, id, false);
        });
    }

    /** Permanent operation identity is the draft ID. Business row, completion receipt and audit commit together. */
    public UUID complete(AuthenticatedIdentity actor, UUID id, long expectedDraft) {
        edit(actor);
        return transaction(connection -> {
            var current = draft(connection, actor, id, true);
            // The expected draft version is the operation fingerprint. A different saved
            // version cannot reuse an already completed operation and claim it was applied.
            if (current.completed()) {
                if (expectedDraft < 0 || current.version() - 1 != expectedDraft)
                    throw new Conflict("This draft completed with a different saved version. Reopen the draft to inspect the saved customer.");
                return current.customerId();
            }
            currentVersion(current, expectedDraft);
            var fields = current.fields().validated();
            var customerId = current.customerId() == null ? UUID.randomUUID() : current.customerId();
            var now = Instant.now().toEpochMilli();
            var version = current.baseVersion() + 1;
            if (current.customerId() == null) {
                try (var insert = statement(connection, "INSERT INTO workflow_customers "
                        + "(customer_id, company, contact_name, email, company_search, version, created_at) VALUES (?, ?, ?, ?, ?, 1, ?)")) {
                    insert.setString(1, customerId.toString()); fields(insert, 2, fields);
                    insert.setString(5, fields.company().toLowerCase(Locale.ROOT)); insert.setLong(6, now); insert.executeUpdate();
                }
            } else {
                try (var update = statement(connection, "UPDATE workflow_customers SET company = ?, contact_name = ?, email = ?, "
                        + "company_search = ?, version = version + 1 WHERE customer_id = ? AND version = ?")) {
                    fields(update, 1, fields); update.setString(4, fields.company().toLowerCase(Locale.ROOT));
                    update.setString(5, customerId.toString()); update.setLong(6, current.baseVersion());
                    if (update.executeUpdate() != 1) throw new Conflict("The customer changed. Your draft is saved; compare the latest values before saving.");
                }
            }
            try (var update = statement(connection, "UPDATE workflow_drafts SET completed = TRUE, customer_id = ?, "
                    + "version = version + 1, updated_at = ? WHERE draft_id = ? AND owner_name = ?")) {
                update.setString(1, customerId.toString()); update.setLong(2, now);
                update.setString(3, id.toString()); update.setString(4, actor.name()); update.executeUpdate();
            }
            try (var audit = statement(connection, "INSERT INTO workflow_audit "
                    + "(operation_id, customer_id, actor, customer_version, committed_at) VALUES (?, ?, ?, ?, ?)")) {
                audit.setString(1, id.toString()); audit.setString(2, customerId.toString()); audit.setString(3, actor.name());
                audit.setLong(4, version); audit.setLong(5, now); audit.executeUpdate();
            }
            return customerId;
        });
    }

    private static void currentVersion(Draft draft, long expected) {
        if (draft.completed()) throw new Conflict("This draft is already complete. Open the saved customer.");
        if (draft.version() != expected) throw new Conflict("This draft changed in another tab. Open its latest saved version before continuing.");
    }
    private static Draft draft(Connection connection, AuthenticatedIdentity actor, UUID id, boolean lock) throws SQLException {
        try (var query = statement(connection, "SELECT * FROM workflow_drafts WHERE draft_id = ? AND owner_name = ?"
                + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id.toString()); query.setString(2, actor.name());
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new Missing();
                return draft(result);
            }
        }
    }
    private static Customer customer(Connection connection, UUID id) throws SQLException {
        try (var query = statement(connection, "SELECT * FROM workflow_customers WHERE customer_id = ?")) {
            query.setString(1, id.toString());
            try (var result = query.executeQuery()) {
                if (!result.next()) throw new Missing();
                return customer(result);
            }
        }
    }
    private static Customer customer(ResultSet row) throws SQLException {
        return new Customer(UUID.fromString(row.getString("customer_id")), fields(row), row.getLong("version"), row.getLong("created_at"));
    }
    private static Draft draft(ResultSet row) throws SQLException {
        var customer = row.getString("customer_id");
        return new Draft(UUID.fromString(row.getString("draft_id")), row.getString("owner_name"),
                customer == null ? null : UUID.fromString(customer), row.getLong("base_version"), fields(row),
                row.getLong("version"), row.getBoolean("completed"), row.getLong("updated_at"));
    }
    private static Fields fields(ResultSet row) throws SQLException {
        return new Fields(row.getString("company"), row.getString("contact_name"), row.getString("email"));
    }
    private static void fields(PreparedStatement statement, int start, Fields fields) throws SQLException {
        statement.setString(start, fields.company()); statement.setString(start + 1, fields.contact()); statement.setString(start + 2, fields.email());
    }
    private static PreparedStatement statement(Connection connection, String sql) throws SQLException {
        var result = connection.prepareStatement(sql);
        result.setQueryTimeout(5);
        return result;
    }
    private interface Work<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(Work<T> work) {
        try (var connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw databaseFailure(failure); }
    }
    private static IllegalStateException databaseFailure(SQLException cause) {
        return new IllegalStateException("Database operation did not return a confirmed result; reopen the saved draft to inspect its status.", cause);
    }
    public static UUID id(String value) {
        var parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException("Use a canonical record ID");
        return parsed;
    }
    private static void read(AuthenticatedIdentity actor) {
        if (actor == null || (!actor.hasRole("EDITOR") && !actor.hasRole("VIEWER"))) throw new SecurityException("Access denied");
    }
    private static void edit(AuthenticatedIdentity actor) {
        if (actor == null || !actor.hasRole("EDITOR")) throw new SecurityException("Editing requires the editor role");
    }
}
