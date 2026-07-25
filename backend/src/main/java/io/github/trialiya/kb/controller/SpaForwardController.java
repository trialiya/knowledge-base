package io.github.trialiya.kb.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards SPA client-side routes to index.html so that direct links and browser refreshes on
 * /chat, /knowledge, /files, /admin and /settings are handled by the React router instead of
 * returning a 500 "No static resource" error.
 *
 * <p>The nested wildcards matter: the frontend keeps the opened resource in the path itself
 * (/chat/&lt;id&gt;, /knowledge/doc/&lt;id&gt;, /files/&lt;path/to/file&gt;), so a plain "/chat"
 * mapping would leave every shared deep link returning an error.
 *
 * <p>All /api/** requests are matched by the REST controllers first (they are registered before the
 * dispatcher servlet's default handler), so this mapping never intercepts actual API calls.
 */
@Controller
public class SpaForwardController {

    @GetMapping({
        "/chat",
        "/chat/**",
        "/knowledge",
        "/knowledge/**",
        "/files",
        "/files/**",
        "/admin",
        "/settings"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
