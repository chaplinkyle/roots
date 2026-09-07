package com.chaplin.roots.load.pages.business;

import com.chaplin.roots.*;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.load.BusinessStore;
import com.chaplin.roots.validation.*;
import java.util.ArrayList;
import static com.chaplin.roots.html.Html.*;

public final class Page implements com.chaplin.roots.Page {
    private String user;
    private final ArrayList<Row> rows = new ArrayList<>();
    private final Pulse pulse = new Pulse();
    @Override public void onMount(PageContext context) {
        user = context.query("user").orElseThrow();
        BusinessStore.read(user).forEach(account -> rows.add(new Row(account)));
        BusinessStore.UPDATES.put(user, () -> {
            long scheduled = System.nanoTime();
            context.update(() -> { pulse.sequence++; pulse.sent = scheduled; });
        });
    }
    @Override public void onUnmount(PageContext context) { BusinessStore.UPDATES.remove(user); }
    @Override public Node render(PageContext context) {
        return main(h1("Database account workload"), pulse,
                form(input().type("hidden").name("account").value(0), input().name("status").value("Active"),
                        button("Save").type("submit")).onSubmit(this, "save"),
                table(tbody(rows)));
    }
    @ServerAction private void save(ActionEvent event) {
        var form = event.bind(Edit.class);
        if (form.account() >= rows.size()) throw new IllegalArgumentException("Unknown account");
        var row = rows.get(form.account());
        row.account = BusinessStore.update(user, form.account(), row.account.version(), form.status());
    }
    @FormModel public record Edit(@Min(0) int account, @NotBlank @Size(max = 40) String status) {}
    private static final class Pulse implements Component {
        long sequence, sent;
        public Node render(PageContext context) { return span(sequence).data("pulse", sequence).data("sent", sent); }
    }
    private final class Row implements Component {
        BusinessStore.Account account;
        Row(BusinessStore.Account account) { this.account = account; }
        public Node render(PageContext context) {
            return tr(td("Account " + account.id()), td("owner" + account.id() + "@example.com"),
                    td("Enterprise"), td(account.status()), td(account.version()),
                    td(button("Approve").onClick(this, "approve"))).key("account-" + account.id());
        }
        @ServerAction private void approve() { account = BusinessStore.update(user, account.id(), account.version(), "Approved"); }
    }
}
