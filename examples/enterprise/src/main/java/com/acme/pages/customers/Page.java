package com.acme.pages.customers;

import com.acme.components.CustomerTable;
import com.acme.components.CustomerTable.Customer;
import dev.roots.ActionEvent;
import dev.roots.PageContext;
import dev.roots.annotation.PageMetadata;
import dev.roots.annotation.ServerAction;
import dev.roots.html.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static dev.roots.html.Html.button;
import static dev.roots.html.Html.div;
import static dev.roots.html.Html.form;
import static dev.roots.html.Html.h1;
import static dev.roots.html.Html.input;
import static dev.roots.html.Html.label;
import static dev.roots.html.Html.option;
import static dev.roots.html.Html.p;
import static dev.roots.html.Html.section;
import static dev.roots.html.Html.select;
import static dev.roots.html.Html.span;

@PageMetadata(
        title = "Customers · Roots Control",
        description = "Manage enterprise customer accounts with live Java components.",
        stylesheets = "/app.css"
)
public final class Page implements dev.roots.Page {
    private final List<Customer> customers = new ArrayList<>(List.of(
            new Customer(1042, "Northstar Freight", "Mina Patel", "Enterprise", "Active"),
            new Customer(1078, "Kestrel Health", "Jon Bell", "Enterprise", "Review"),
            new Customer(1124, "Lake & Field", "Sofia Reyes", "Growth", "Active"),
            new Customer(1191, "Copperline Works", "Evan Cho", "Growth", "Paused")
    ));
    private String filter = "All";
    private long nextId = 1200;

    @Override
    public Node render(PageContext context) {
        var visible = customers.stream()
                .filter(customer -> filter.equals("All") || customer.status().equals(filter))
                .toList();

        return div(
                section(
                        div(span("ACCOUNT DIRECTORY").className("eyebrow"), h1("Customers"),
                                p("Account state lives in Java for this demo; swap the list for your repository or service.")),
                        form(
                                label("Company", input().name("company").placeholder("Acme Industries").attr("required", true)),
                                label("Owner", input().name("owner").placeholder("Taylor Morgan").attr("required", true)),
                                button("Add customer").type("submit").className("button primary")
                        ).className("create-form").onSubmit(this, "create")
                ).className("page-heading customers-heading"),
                section(
                        div(
                                div(span(visible.size()).className("count"), span("accounts shown")),
                                label("Filter",
                                        select(
                                                statusOption("All"),
                                                statusOption("Active"),
                                                statusOption("Review"),
                                                statusOption("Paused")
                                        ).name("status").onChange(this, "filter")
                                ).className("filter-control")
                        ).className("table-toolbar"),
                        new CustomerTable(visible)
                ).className("table-panel")
        ).className("page customers-page");
    }

    private Node statusOption(String status) {
        return option(status).value(status).attr("selected", filter.equals(status));
    }

    @ServerAction("create")
    private void createCustomer(ActionEvent event) {
        var company = event.required("company").strip();
        var owner = event.required("owner").strip();
        customers.addFirst(new Customer(nextId++, company, owner, "Growth", "Review"));
    }

    @ServerAction("filter")
    private void filterCustomers(ActionEvent event) {
        var requested = event.required("status");
        filter = switch (requested.toLowerCase(Locale.ROOT)) {
            case "active" -> "Active";
            case "review" -> "Review";
            case "paused" -> "Paused";
            default -> "All";
        };
    }
}
