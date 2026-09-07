package com.acme.components;

import com.chaplin.roots.Component;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.annotation.ViewComponent;
import com.chaplin.roots.html.Node;

import java.util.List;

import static com.chaplin.roots.html.Html.link;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.table;
import static com.chaplin.roots.html.Html.tbody;
import static com.chaplin.roots.html.Html.td;
import static com.chaplin.roots.html.Html.th;
import static com.chaplin.roots.html.Html.thead;
import static com.chaplin.roots.html.Html.tr;

@ViewComponent("customer-table")
public record CustomerTable(List<Customer> customers) implements Component {
    public CustomerTable {
        customers = List.copyOf(customers);
    }

    @Override
    public Node render(PageContext context) {
        return table(
                thead(tr(
                        th("Account"),
                        th("Owner"),
                        th("Plan"),
                        th("State")
                )),
                tbody(customers.stream().map(customer -> tr(
                        td(link("/customers/" + customer.id(), customer.name()).className("table-link")),
                        td(customer.owner()),
                        td(customer.plan()),
                        td(span(customer.status()).className("status " + customer.status().toLowerCase()))
                )).toList())
        ).className("data-table");
    }

    public record Customer(long id, String name, String owner, String plan, String status) {
    }
}
