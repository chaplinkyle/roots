package com.chaplin.roots.examples.kanban;

import com.chaplin.roots.AuthenticatedIdentity;
import com.chaplin.roots.ValidationException;
import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/** One shared, authenticated workspace. Every mutation and its activity entry commit together. */
public final class TaskRepository {
    public enum Status {
        BACKLOG("Backlog"), READY("Ready"), IN_PROGRESS("In progress"), DONE("Done");
        public final String label;
        Status(String label) { this.label = label; }
    }
    public enum Priority { LOW, MEDIUM, HIGH, URGENT }
    public record Fields(String title, String description, Status status, Priority priority, String assignee, LocalDate due) {
        public Fields {
            title = clean(title, "title", 160, true);
            description = clean(description, "description", 8000, false);
            assignee = clean(assignee, "assignee", 80, false);
            Objects.requireNonNull(status); Objects.requireNonNull(priority);
            if (due != null && (due.getYear() < 1900 || due.getYear() > 9999))
                throw ValidationException.field("due", "Choose a date between 1900 and 9999.");
        }
    }
    public record Task(String id, long number, Fields fields, boolean archived, long version, String updatedBy) {}
    public record Activity(String actor, String action, String at) {}
    public static final class Missing extends RuntimeException {}
    public static final class Conflict extends RuntimeException {
        public Conflict() { super("This task changed in another tab. Your changes were not saved. Copy any text you want to keep, then reopen the task to review the latest version."); }
    }
    private final DataSource pool;
    public TaskRepository(DataSource pool) { this.pool = pool; }

    public List<Task> list(AuthenticatedIdentity actor, String search, boolean archived) {
        read(actor);
        if (search.length() > 160) search = search.substring(0, 160);
        String pattern = "%" + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        try (var c = pool.getConnection(); var s = statement(c,
                "SELECT * FROM kanban_tasks WHERE archived=? AND (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(assignee) LIKE ? ESCAPE '!') ORDER BY number DESC LIMIT 501")) {
            s.setBoolean(1, archived); s.setString(2, pattern); s.setString(3, pattern);
            try (var r = s.executeQuery()) { var tasks = new ArrayList<Task>(); while (r.next()) tasks.add(task(r)); return List.copyOf(tasks); }
        } catch (SQLException e) { throw database(e); }
    }

    public Task get(AuthenticatedIdentity actor, String id) {
        read(actor); UUID.fromString(id);
        try (var c = pool.getConnection()) { return get(c, id); } catch (SQLException e) { throw database(e); }
    }

    public List<Activity> activity(AuthenticatedIdentity actor, String id) {
        read(actor); UUID.fromString(id);
        try (var c = pool.getConnection(); var s = statement(c,
                "SELECT actor,action,occurred_at FROM kanban_activity WHERE task_id=? ORDER BY id DESC LIMIT 12")) {
            s.setString(1,id);
            try (var r=s.executeQuery()) { var items=new ArrayList<Activity>(); while(r.next()) items.add(new Activity(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant().toString())); return List.copyOf(items); }
        } catch(SQLException e) { throw database(e); }
    }

    public String create(AuthenticatedIdentity actor, UUID operation, Fields fields) {
        edit(actor); var id = operation.toString();
        try (var c=pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var s=statement(c,"INSERT INTO kanban_tasks (id,title,description,status,priority,assignee,due_date,updated_by) VALUES (?,?,?,?,?,?,?,?)")) {
                    s.setString(1,id); fields(s,2,fields); s.setString(8,actor.name()); s.executeUpdate();
                }
                log(c,id,actor,"Created task"); c.commit(); return id;
            } catch(SQLException | RuntimeException e) {
                c.rollback();
                // This UUID belongs to the server page, making a lost-response retry harmless.
                if(e instanceof SQLException sql && "23505".equals(sql.getSQLState())) { get(c,id); return id; }
                throw e;
            }
        } catch(SQLException e) { throw database(e); }
    }

    public void save(AuthenticatedIdentity actor, String id, long version, Fields fields) {
        edit(actor);
        mutate(actor,id,version,"Updated task", (c) -> {
            try(var s=statement(c,"UPDATE kanban_tasks SET title=?,description=?,status=?,priority=?,assignee=?,due_date=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=? AND archived=FALSE")) {
                fields(s,1,fields); s.setString(7,actor.name()); s.setString(8,id); s.setLong(9,version); return s.executeUpdate();
            }
        });
    }

    public void move(AuthenticatedIdentity actor, String id, long version, Status status) {
        edit(actor);
        mutate(actor,id,version,"Moved to " + status.label,c -> {
            try(var s=statement(c,"UPDATE kanban_tasks SET status=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=? AND archived=FALSE")) {
                s.setString(1,status.name()); s.setString(2,actor.name()); s.setString(3,id); s.setLong(4,version); return s.executeUpdate();
            }
        });
    }

    public void archive(AuthenticatedIdentity actor, String id, long version, boolean archived) {
        edit(actor);
        mutate(actor,id,version,archived ? "Archived task" : "Restored task",c -> {
            try(var s=statement(c,"UPDATE kanban_tasks SET archived=?,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?")) {
                s.setBoolean(1,archived); s.setString(2,actor.name()); s.setString(3,id); s.setLong(4,version); return s.executeUpdate();
            }
        });
    }

    @FunctionalInterface private interface Change { int apply(Connection c) throws SQLException; }
    private void mutate(AuthenticatedIdentity actor, String id, long version, String action, Change change) {
        UUID.fromString(id);
        try(var c=pool.getConnection()) {
            c.setAutoCommit(false);
            try { if(change.apply(c)!=1) throw new Conflict(); log(c,id,actor,action); c.commit(); }
            catch(SQLException | RuntimeException e) { c.rollback(); throw e; }
        } catch(SQLException e) { throw database(e); }
    }
    private static Task get(Connection c,String id) throws SQLException {
        try(var s=statement(c,"SELECT * FROM kanban_tasks WHERE id=?")) {
            s.setString(1,id); try(var r=s.executeQuery()) { if(!r.next()) throw new Missing(); return task(r); }
        }
    }
    private static void log(Connection c,String id,AuthenticatedIdentity actor,String action) throws SQLException {
        try(var s=statement(c,"INSERT INTO kanban_activity (task_id,actor,action) VALUES (?,?,?)")) { s.setString(1,id); s.setString(2,actor.name()); s.setString(3,action); s.executeUpdate(); }
    }
    private static PreparedStatement statement(Connection c,String sql) throws SQLException { var s=c.prepareStatement(sql); s.setQueryTimeout(5); return s; }
    private static void fields(PreparedStatement s,int offset,Fields f) throws SQLException {
        s.setString(offset,f.title()); s.setString(offset+1,f.description()); s.setString(offset+2,f.status().name()); s.setString(offset+3,f.priority().name()); s.setString(offset+4,f.assignee());
        s.setObject(offset+5,f.due());
    }
    private static Task task(ResultSet r) throws SQLException {
        return new Task(r.getString("id"),r.getLong("number"),new Fields(r.getString("title"),r.getString("description"),Status.valueOf(r.getString("status")),Priority.valueOf(r.getString("priority")),r.getString("assignee"),r.getObject("due_date",LocalDate.class)),r.getBoolean("archived"),r.getLong("version"),r.getString("updated_by"));
    }
    private static String clean(String value,String field,int max,boolean required) {
        value=Objects.requireNonNullElse(value,"").strip();
        if((required && value.isEmpty()) || value.length()>max || value.indexOf('\0')>=0)
            throw ValidationException.field(field, required ? "Enter a title of 1 to " + max + " characters." : "Use at most " + max + " characters.");
        return value;
    }
    private static void read(AuthenticatedIdentity actor) { if(!actor.hasRole("EDITOR") && !actor.hasRole("VIEWER")) throw new SecurityException("Access denied"); }
    private static void edit(AuthenticatedIdentity actor) { if(!actor.hasRole("EDITOR")) throw new SecurityException("Editor access required"); }
    private static IllegalStateException database(SQLException e) { return new IllegalStateException("The database is unavailable. Try again shortly.",e); }
}
