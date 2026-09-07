package com.chaplin.roots.testapp.api.form;

import com.chaplin.roots.ApiRoute;
import com.chaplin.roots.Request;
import com.chaplin.roots.Response;
import com.chaplin.roots.validation.Email;
import com.chaplin.roots.validation.FormField;
import com.chaplin.roots.validation.FormModel;
import com.chaplin.roots.validation.Min;
import com.chaplin.roots.validation.NotBlank;

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
