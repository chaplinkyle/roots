package com.acme.components;

import dev.roots.Component;
import dev.roots.PageContext;
import dev.roots.annotation.ViewComponent;
import dev.roots.html.Node;

import java.util.List;

import static dev.roots.html.Html.link;
import static dev.roots.html.Html.span;
import static dev.roots.html.Html.table;
import static dev.roots.html.Html.tbody;
import static dev.roots.html.Html.td;
import static dev.roots.html.Html.th;
import static dev.roots.html.Html.thead;
import static dev.roots.html.Html.tr;

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
