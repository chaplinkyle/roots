package com.chaplin.roots.internal;

import com.chaplin.roots.Roots;

final class Protocol {
    static final String VERSION = Roots.PROTOCOL_VERSION;
    static final String FORM_FIELD = "_protocol";
    static final String QUERY_PARAMETER = "protocol";
    static final String RESPONSE_HEADER = "X-Roots-Protocol";

    private Protocol() {
    }

    static boolean matches(String value) {
        return VERSION.equals(value);
    }
}
