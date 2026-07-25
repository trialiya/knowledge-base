package io.github.trialiya.kb.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pins the deep-link contract the frontend's path-based URL scheme depends on: every nested route
 * (/chat/&lt;id&gt;, /knowledge/doc/&lt;id&gt;, /files/&lt;path/to/file&gt;, ...) must forward to
 * index.html rather than 404ing, or a shared link / browser refresh on one of those routes breaks.
 *
 * <p>Security filters are disabled ({@code addFilters = false}): this controller's job is routing,
 * not authorization, and loading the app's real security configuration here would pull in unrelated
 * beans this slice has no need of. Asserting the forward target (rather than following it and
 * expecting real content) keeps the test independent of the frontend build having run — {@code
 * index.html} only exists on the classpath after {@code :frontend:copyFrontend}, which {@code
 * :backend:test} does not depend on.
 */
@WebMvcTest(SpaForwardController.class)
@AutoConfigureMockMvc(addFilters = false)
class SpaForwardControllerTest {

    @Autowired private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/chat",
                "/chat/abc-123",
                "/knowledge",
                "/knowledge/doc/42",
                "/knowledge/search",
                "/files",
                "/files/backend/build.gradle",
                "/admin",
                "/settings",
            })
    void forwardsSpaRoutesToIndexHtml(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(forwardedUrl("/index.html"));
    }

    // Note: this slice loads only SpaForwardController, so it cannot verify the javadoc's other
    // claim (that /api/** is matched by the real REST controllers first, before this catch-all).
    // Asserting that here would just test Spring MVC's no-handler-found plumbing in an artificially
    // narrow context, not the actual controller registration order — that guarantee is exercised
    // for real by every test and manual check that hits /api/** end to end in the full application.
}
