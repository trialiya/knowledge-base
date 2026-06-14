package io.github.trialiya.kb.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards SPA client-side routes to index.html so that direct links and browser refreshes on
 * /chat, /knowledge, /admin and /settings are handled by the React router instead of returning a
 * 500 "No static resource" error.
 *
 * <p>All /api/** requests are matched by the REST controllers first (they are registered before the
 * dispatcher servlet's default handler), so this mapping never intercepts actual API calls.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/chat", "/knowledge", "/admin", "/settings"})
    public String forward() {
        return "forward:/index.html";
    }
}
