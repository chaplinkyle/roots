package com.acme.pages.customers;

import com.acme.components.CustomerTable;
import com.acme.components.CustomerTable.Customer;
import com.chaplin.roots.ActionEvent;
import com.chaplin.roots.PageContext;
import com.chaplin.roots.UploadedFile;
import com.chaplin.roots.annotation.PageMetadata;
import com.chaplin.roots.annotation.ServerAction;
import com.chaplin.roots.html.Node;
import com.chaplin.roots.validation.FormField;
import com.chaplin.roots.validation.FormModel;
import com.chaplin.roots.validation.NotBlank;
import com.chaplin.roots.validation.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.chaplin.roots.html.Html.button;
import static com.chaplin.roots.html.Html.div;
import static com.chaplin.roots.html.Html.form;
import static com.chaplin.roots.html.Html.h1;
import static com.chaplin.roots.html.Html.input;
import static com.chaplin.roots.html.Html.label;
import static com.chaplin.roots.html.Html.option;
import static com.chaplin.roots.html.Html.p;
import static com.chaplin.roots.html.Html.section;
import static com.chaplin.roots.html.Html.select;
import static com.chaplin.roots.html.Html.span;
import static com.chaplin.roots.html.Html.validationMessage;
import static com.chaplin.roots.html.Html.validationSummary;

@PageMetadata(
        title = "Customers · Roots Control",
        description = "Manage enterprise customer accounts with live Java components.",
        stylesheets = "/app.css"
)
public final class Page implements com.chaplin.roots.Page {
    private final List<Customer> customers = new ArrayList<>(List.of(
            new Customer(1042, "Northstar Freight", "Mina Patel", "Enterprise", "Active"),
            new Customer(1078, "Kestrel Health", "Jon Bell", "Enterprise", "Review"),
            new Customer(1124, "Lake & Field", "Sofia Reyes", "Growth", "Active"),
            new Customer(1191, "Copperline Works", "Evan Cho", "Growth", "Paused")
    ));
    private String filter = "All";
    private String search = "";
    private String lastAttachment;
    private long nextId = 1200;

    @Override
    public Node render(PageContext context) {
        var visible = customers.stream()
                .filter(customer -> filter.equals("All") || customer.status().equals(filter))
                .filter(customer -> search.isBlank()
                        || customer.name().toLowerCase(Locale.ROOT).contains(search)
                        || customer.owner().toLowerCase(Locale.ROOT).contains(search))
                .toList();

        return div(
                section(
                        div(span("ACCOUNT DIRECTORY").className("eyebrow"), h1("Customers"),
                                p("Account state lives in Java for this demo; swap the list for your repository or service.")),
                        form(
                                div(
                                        label("Company", input().name("company").placeholder("Acme Industries")),
                                        validationMessage("company")
                                ).className("form-field"),
                                div(
                                        label("Owner", input().name("owner").placeholder("Taylor Morgan")),
                                        validationMessage("owner")
                                ).className("form-field"),
                                label("Supporting file (optional)", input().type("file").name("attachment")
                                        .attr("accept", ".pdf,.txt,.csv")).className("file-field"),
                                lastAttachment == null ? null
                                        : p("Received ", lastAttachment).className("upload-status"),
                                validationSummary().className("validation-summary"),
                                button("Add customer").type("submit").className("button primary")
                        ).className("create-form").onSubmit(this, "create")
                ).className("page-heading customers-heading"),
                section(
                        div(
                                div(span(visible.size()).className("count"), span("accounts shown")),
                                label("Search",
                                        input().name("query").value(search).placeholder("Company or owner")
                                                .onInput(this, "search")
                                ).className("filter-control"),
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
        var form = event.bind(CreateCustomerForm.class);
        customers.addFirst(new Customer(nextId++, form.company(), form.owner(), "Growth", "Review"));
        form.attachment().ifPresent(file ->
                lastAttachment = file.filename() + " · " + file.size() + " bytes");
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

    @ServerAction("search")
    private void searchCustomers(ActionEvent event) {
        search = event.value("query").orElse("").strip().toLowerCase(Locale.ROOT);
    }

    @FormModel(message = "Check the highlighted customer details.")
    private record CreateCustomerForm(
            @FormField(trim = true)
            @NotBlank(message = "Enter a company name.")
            @Size(max = 120, message = "Use at most {max} characters.") String company,
            @FormField(trim = true)
            @NotBlank(message = "Enter an account owner.")
            @Size(max = 120, message = "Use at most {max} characters.") String owner,
            Optional<UploadedFile> attachment
    ) {
    }
}
