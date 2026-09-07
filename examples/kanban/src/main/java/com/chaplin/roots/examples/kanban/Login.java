package com.chaplin.roots.examples.kanban;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.util.HtmlUtils;

@Controller
final class Login {
    @GetMapping("/") String home() { return "redirect:/app/"; }
    @GetMapping(value="/login",produces="text/html;charset=UTF-8")
    @ResponseBody String login(HttpServletRequest request) {
        var csrf=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        var notice=request.getParameter("error")!=null ? "The username or password did not match. Try again." : request.getParameter("logout")!=null ? "You’re signed out." : "";
        return """
            <!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>Sign in | Roots</title><link rel="stylesheet" href="/login.css"></head>
            <body><main><section class="intro"><a class="brand" href="/">roots</a><div><p class="eyebrow">Your team’s next step</p><h1>Good work<br>takes roots.</h1><p>One shared board to turn the things you’re thinking about into the things you’ve shipped.</p><div class="lanes" aria-hidden="true"><div><i></i><b></b></div><div><i></i><b></b><b></b></div><div><i></i><b></b></div></div></div><small>Make progress, together.</small></section>
            <section class="signin"><div><h2>Welcome back</h2><p>Sign in to your engineering workspace.</p><p class="notice" role="status">%s</p><form method="post" action="/login"><input type="hidden" name="%s" value="%s"><label>Username<input name="username" autocomplete="username" required autofocus></label><label>Password<input type="password" name="password" autocomplete="current-password" required></label><button type="submit">Sign in</button></form><p class="help">Need access? Contact your workspace administrator.</p></div></section></main></body></html>
            """.formatted(HtmlUtils.htmlEscape(notice),HtmlUtils.htmlEscape(csrf.getParameterName()),HtmlUtils.htmlEscape(csrf.getToken()));
    }
}
