package dev.roots.testapp.api.form;

import dev.roots.ApiRoute;
import dev.roots.Request;
import dev.roots.Response;
import dev.roots.validation.Email;
import dev.roots.validation.FormField;
import dev.roots.validation.FormModel;
import dev.roots.validation.Min;
import dev.roots.validation.NotBlank;

public final class Route implements ApiRoute {
    public Route(String dependency) {
    }

    @Override
    public Response post(Request request) {
        var form = request.bind(ApiForm.class);
        return Response.text(201, form.email() + ":" + form.quantity());
    }

    @FormModel(message = "Check the API form.")
    private record ApiForm(
            @FormField(trim = true) @NotBlank @Email String email,
            @Min(value = 1, message = "Order at least {value} item.") int quantity
    ) {
    }
}
