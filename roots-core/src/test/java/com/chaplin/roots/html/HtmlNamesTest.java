package com.chaplin.roots.html;

import org.junit.jupiter.api.Test;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

final class HtmlNamesTest {
    @Test void preservesPreviousAsciiGrammarsForEveryUtf16CodeUnit() {
        var tag = Pattern.compile("[a-z][a-z0-9-]*");
        var attribute = Pattern.compile("[A-Za-z_:][A-Za-z0-9_.:-]*");
        var action = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]*");
        for (int code = 0; code <= Character.MAX_VALUE; code++) {
            for (var value : new String[]{String.valueOf((char) code), "a" + (char) code, (char) code + "x"}) {
                assertEquals(tag.matcher(value).matches(), HtmlNames.tag(value), "tag code " + code);
                assertEquals(attribute.matcher(value).matches(), HtmlNames.attribute(value), "attribute code " + code);
                assertEquals(action.matcher(value).matches(), HtmlNames.action(value), "action code " + code);
            }
        }
        assertFalse(HtmlNames.tag("")); assertFalse(HtmlNames.attribute("")); assertFalse(HtmlNames.action(""));
    }
}
