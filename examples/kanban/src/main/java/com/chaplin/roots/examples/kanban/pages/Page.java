package com.chaplin.roots.examples.kanban.pages;

import com.chaplin.roots.*;
import com.chaplin.roots.annotation.*;
import com.chaplin.roots.examples.kanban.TaskRepository;
import com.chaplin.roots.examples.kanban.TaskRepository.*;
import com.chaplin.roots.html.Node;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.chaplin.roots.html.Html.*;

@Authorize("read")
@PageMetadata(title="Board | Roots",stylesheets="/kanban.css",robots="noindex, nofollow",themeColor="#182A40")
public final class Page implements com.chaplin.roots.Page {
    private final TaskRepository repository;
    private final UUID createId=UUID.randomUUID();
    private Task opened;
    private String notice;
    private long revision;
    public Page(TaskRepository repository) { this.repository=repository; }

    @Override public Node render(PageContext context) {
        var actor=context.identity().orElseThrow();
        boolean canEdit=actor.hasRole("EDITOR"), archived=context.query("archived").isPresent();
        String search=context.query("q").orElse("");
        var found=repository.list(actor,search,archived);
        var tasks=found.stream().limit(500).toList();
        var editId=context.query("edit");
        boolean editing=editId.isPresent() || context.query("new").isPresent();
        if(editId.isPresent() && opened==null) {
            try { opened=repository.get(actor,editId.get()); } catch(IllegalArgumentException e) { throw new Missing(); }
        }
        var key="board-"+(++revision);
        return div(
            header(div(span("Workspace").className("muted"),span("/"),span(archived ? "Archive" : "Board")).className("breadcrumb"),
                span("Shared team board").className("workspace-label")).className("topbar"),
            section(div(div(h1(archived ? "Archived tasks" : "Engineering board"),p(archived ? "Finished for now. Restore a task whenever you need it." : "From the first idea to the final ship.")),
                    canEdit && !archived ? a(span("+"),"New task").href("/?new=1").className("button primary").id("new-task") : null).className("heading"),
                div(form(label(span("Search tasks").className("sr-only"),input().type("search").name("q").value(search).attr("placeholder","Search tasks or assignees…").attr("maxlength","160")),
                            archived ? input().type("hidden").name("archived").value("1") : null,
                            button("Search").type("submit").className("button quiet")).attr("method","get").attr("action","/").className("search"),
                    div(span(tasks.size()+" tasks").className("muted"),button("Refresh board").onClick(this,"refresh").className("button quiet")).className("toolbar-right")).className("toolbar"),
                notice==null ? null : p(notice).className("notice").attr("role","status"),
                found.size()>500 ? p("Showing 500 tasks. Search by title or assignee to narrow the board.").className("notice") : null,
                !canEdit ? p("You have read-only access.").className("notice") : null,
                form(input().type("hidden").name("taskId").value(""),input().type("hidden").name("version").value(""),input().type("hidden").name("status").value(""),
                    button("Move task").type("submit")).onSubmit(this,"move").id("move-task").attr("hidden",true),
                div(Arrays.stream(Status.values()).map(status -> column(status,tasks,canEdit && !archived)).toList())
                    .className("board").id("board").widget(key,"/board.js").data("widget-editable",Boolean.toString(canEdit && !archived)),
                p(archived ? "Open a task to restore it to the board." : "Drag tasks between columns, or open a task to change its status. Refresh to see teammates’ latest changes.").className("board-hint"),
                tasks.isEmpty() ? null : tag("details",tag("summary","Open a task by name"),
                    form(label("Task",select(tasks.stream().map(t -> option(t.fields().title()).value(t.id())).toList()).name("edit")),button("Open task").type("submit").className("button quiet"))
                        .attr("method","get").attr("action","/").className("search")).className("task-picker")
            ).className("board-workspace"),
            editing ? editor(context,canEdit) : null
        );
    }

    private Node column(Status status,List<Task> tasks,boolean draggable) {
        var items=tasks.stream().filter(t -> t.fields().status()==status).toList();
        return section(div(h2(span().className("status-dot "+status.name().toLowerCase(Locale.ROOT)),status.label),span(items.size()).className("count")).className("column-heading"),
            div(items.isEmpty() ? div(p("No tasks here"),small(status==Status.BACKLOG ? "Make room for your next idea." : "Move a task here when it’s ready.")).className("empty-column")
                : items.stream().map(t -> card(t,draggable)).toList()).className("column-cards")
        ).className("column").data("status",status.name()).attr("aria-label",status.label);
    }

    private Node card(Task t,boolean draggable) {
        var f=t.fields();
        return article(div(span("RTS-"+t.number()).className("task-number"),span(f.priority().name().toLowerCase(Locale.ROOT)).className("priority "+f.priority().name().toLowerCase(Locale.ROOT))).className("card-top"),
            span(f.title()).className("task-title").data("edit-id",t.id()),
            f.description().isBlank() ? null : p(f.description().length()>110 ? f.description().substring(0,110)+"…" : f.description()).className("task-description"),
            div(f.due()==null ? span("No due date").className("due muted") : span(f.due().format(DateTimeFormatter.ofPattern("MMM d",Locale.ENGLISH))).className("due"),
                span(f.assignee().isBlank() ? "Unassigned" : f.assignee()).className("assignee")).className("card-bottom")
        ).className("task-card").data("task-id",t.id()).data("version",Long.toString(t.version())).attr("draggable",Boolean.toString(draggable));
    }

    private Node editor(PageContext context,boolean canEdit) {
        var f=opened==null ? new Fields("New task","",Status.BACKLOG,Priority.MEDIUM,"",null) : opened.fields();
        boolean locked=!canEdit || (opened!=null && opened.archived());
        return aside(
            div(div(small(opened==null ? "Create a task" : "RTS-"+opened.number()),h2(opened==null ? "What’s next?" : "Task details")),a("Close").href(opened!=null && opened.archived() ? "/?archived=1" : "/").className("button quiet")).className("editor-heading"),
            form(input().type("hidden").name("version").value(opened==null ? "0" : Long.toString(opened.version())),
                label("Title",input().name("title").value(opened==null ? "" : f.title()).attr("maxlength","160").attr("required",true).attr("autofocus",true).attr("disabled",locked)),validationMessage("title"),
                label("Description",textarea(f.description()).name("description").attr("rows","6").attr("maxlength","8000").attr("placeholder","Add context, acceptance criteria, or a useful link.").attr("disabled",locked)),validationMessage("description"),
                div(label("Status",select(Arrays.stream(Status.values()).map(s -> option(s.label).value(s.name()).attr("selected",f.status()==s)).toList()).name("status").attr("disabled",locked)),
                    label("Priority",select(Arrays.stream(Priority.values()).map(p -> option(p.name().substring(0,1)+p.name().substring(1).toLowerCase(Locale.ROOT)).value(p.name()).attr("selected",f.priority()==p)).toList()).name("priority").attr("disabled",locked))).className("field-row"),
                label("Assignee",input().name("assignee").value(f.assignee()).attr("maxlength","80").attr("placeholder","Name or team").attr("disabled",locked)),validationMessage("assignee"),
                label("Due date",input().type("date").name("due").value(f.due()==null ? "" : f.due().toString()).attr("min","1900-01-01").attr("max","9999-12-31").attr("disabled",locked)),validationMessage("due"),validationSummary(),
                locked ? null : button(opened==null ? "Create task" : "Save changes").type("submit").className("button primary full")
            ).onSubmit(this,"save").pendingScope().className("task-form"),
            opened!=null && canEdit ? form(input().type("hidden").name("version").value(Long.toString(opened.version())),button(opened.archived() ? "Restore to board" : "Archive task").type("submit").className("button quiet full")).onSubmit(this,"archive") : null,
            opened==null ? p("Tasks are shared with everyone in this workspace.").className("muted") :
                section(h3("Activity"),ul(repository.activity(context.identity().orElseThrow(),opened.id()).stream().map(a -> li(strong(a.action()),small(a.actor()),tag("time",a.at().replace("T"," ").replace("Z"," UTC")).attr("datetime",a.at()))).toList())).className("activity")
        ).className("task-editor").attr("aria-label","Task editor");
    }

    @ServerAction("refresh") private void refresh() { revision++; notice="Board refreshed."; }
    @ServerAction("move") @Authorize("edit") private void move(ActionEvent event) {
        try { repository.move(event.identity().orElseThrow(),field(event,"taskId"),version(event),Status.valueOf(field(event,"status"))); notice="Task moved."; }
        catch(TaskRepository.Conflict e) { notice="This task changed before the move was saved. The board now shows its latest position; move it again if needed."; }
        revision++;
    }
    @ServerAction("save") @Authorize("edit") private void save(ActionEvent event) {
        LocalDate due=null;
        try { if(!field(event,"due").isBlank()) due=LocalDate.parse(field(event,"due")); }
        catch(java.time.DateTimeException e) { throw ValidationException.field("due","Choose a valid date."); }
        Fields f;
        try { f=new Fields(field(event,"title"),field(event,"description"),Status.valueOf(field(event,"status")),Priority.valueOf(field(event,"priority")),field(event,"assignee"),due); }
        catch(IllegalArgumentException e) { throw ValidationException.field("title","Choose a valid status and priority."); }
        try {
            if(opened==null) repository.create(event.identity().orElseThrow(),createId,f);
            else repository.save(event.identity().orElseThrow(),opened.id(),version(event),f);
            event.redirect("/");
        } catch(TaskRepository.Conflict e) { throw ValidationException.field("title",e.getMessage()); }
    }
    @ServerAction("archive") @Authorize("edit") private void archive(ActionEvent event) {
        if(opened==null) throw new Missing();
        try { repository.archive(event.identity().orElseThrow(),opened.id(),version(event),!opened.archived()); event.redirect(opened.archived() ? "/" : "/?archived=1"); }
        catch(TaskRepository.Conflict e) { throw ValidationException.field("title",e.getMessage()); }
    }
    private static String field(ActionEvent e,String name) { if(e.values(name).size()!=1) throw new IllegalArgumentException("Submit each field exactly once"); return e.value(name).orElseThrow(); }
    private static long version(ActionEvent e) { return Long.parseLong(field(e,"version")); }
}
