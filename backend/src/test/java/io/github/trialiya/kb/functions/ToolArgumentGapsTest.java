package io.github.trialiya.kb.functions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.AttachmentService;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.SearchAgentService;
import io.github.trialiya.kb.service.script.ScriptRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * What happens when a weak model calls a tool and fills in nothing at all.
 *
 * <p>Spring AI hands an absent argument to {@code Method.invoke} as {@code null}. For a wrapper
 * type that is harmless — the tool body sees it and answers. For a <b>primitive</b> parameter
 * reflection throws {@code IllegalArgumentException("argument type mismatch")} from {@code invoke}
 * itself, which is therefore <em>not</em> wrapped in {@link ToolExecutionException}, never reaches
 * the {@code ToolExecutionExceptionProcessor}, and kills the whole chat run instead of one call.
 *
 * <p>So the invariant pinned here is not "every tool validates" but the stronger, mechanical one:
 * <b>an argument-less call is always answerable</b>. Whatever a tool decides — default it, or
 * refuse it — the failure has to arrive through the framework's error channel, naming an argument
 * the model can go and fill in. Adding a primitive to a {@code @Tool} signature fails this test.
 */
class ToolArgumentGapsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolContext context =
            new ToolContext(Map.of(ChatMemory.CONVERSATION_ID, "test-chat"));

    /** Every {@code @Tool} the chat model can see, over mocked services. */
    private static ToolCallback[] allTools() {
        return ToolCallbacks.from(
                new DocumentFunction(mock(DocumentService.class), mock(AttachmentService.class)),
                new AttachmentFunction(mock(AttachmentService.class)),
                new GitFunction(mock(GitService.class)),
                new GitEditFunction(mock(GitService.class)),
                new MessageLookupFunction(mock(ChatMessageRepository.class)),
                new SearchAgentFunction(mock(SearchAgentService.class)),
                new TopicFunction(mock(ChatTopicRepository.class)),
                ScriptFunction.forChat(mock(ScriptRunner.class)));
    }

    @Test
    void noToolCanBringDownTheRunByBeingCalledWithNoArguments() {
        final List<String> broken = new ArrayList<>();
        for (ToolCallback tool : allTools()) {
            final String name = tool.getToolDefinition().name();
            try {
                tool.call("{}", context);
            } catch (ToolExecutionException e) {
                // The good failure: wrapped, so the processor turns it into a tool result the
                // model reads and retries from.
                final String message = String.valueOf(rootCause(e).getMessage());
                if (message.contains("argument type mismatch")) {
                    broken.add(name + " → reflection rejected a null primitive: " + message);
                }
            } catch (Exception e) {
                broken.add(
                        name
                                + " → escaped as "
                                + e.getClass().getSimpleName()
                                + ": "
                                + e.getMessage());
            }
        }
        assertThat(broken)
                .describedAs("tools that fail outside the ToolExecutionException channel")
                .isEmpty();
    }

    @Test
    void refusingAnEmptyCallNamesAnArgumentTheModelCanFillIn() {
        final List<String> unhelpful = new ArrayList<>();
        for (ToolCallback tool : allTools()) {
            final String name = tool.getToolDefinition().name();
            final List<String> required = requiredParams(tool);
            try {
                tool.call("{}", context);
                // A tool with no required arguments is allowed to just work on defaults.
                if (!required.isEmpty()) {
                    unhelpful.add(name + " → accepted an empty call despite requiring " + required);
                }
            } catch (ToolExecutionException e) {
                final String message = String.valueOf(rootCause(e).getMessage());
                if (required.stream().noneMatch(message::contains)) {
                    unhelpful.add(name + " → " + message + " (names none of " + required + ")");
                }
            } catch (Exception ignored) {
                // Covered by noToolCanBringDownTheRunByBeingCalledWithNoArguments.
            }
        }
        assertThat(unhelpful)
                .describedAs("tools whose refusal does not say which argument is missing")
                .isEmpty();
    }

    @Test
    void anOptionalFlagLeftOutIsJustItsDefault() {
        // getUncommittedChanges used to take a primitive boolean: this call is the one that broke
        // the run outright, and it now answers with includePatch=false.
        final ToolCallback tool = toolNamed("getUncommittedChanges");
        assertThat(tool.call("{}", context)).isNotNull();
    }

    @Test
    void aMistypedIdIsRefusedByNameInsteadOfAsANumberFormatException() {
        final ToolCallback tool = toolNamed("getDocument");
        try {
            tool.call("{\"documentId\": \"doc-7\"}", context);
            fail("expected a refusal");
        } catch (ToolExecutionException e) {
            assertThat(rootCause(e).getMessage())
                    .contains("documentId")
                    .contains("doc-7")
                    .doesNotContain("For input string");
        }
    }

    @Test
    void aLowercaseEnumValueIsUnderstoodRatherThanRejectedByTheDeserializer() {
        // Argument conversion happens for the whole map before the method runs, so if "before"
        // were still refused we would read a Jackson message here instead of the missing-id one.
        final ToolCallback tool = toolNamed("insertDocumentSection");
        try {
            tool.call("{\"position\": \"before\"}", context);
            fail("expected a refusal");
        } catch (ToolExecutionException e) {
            assertThat(rootCause(e).getMessage())
                    .contains("documentId")
                    .doesNotContainIgnoringCase("InsertPosition");
        }
    }

    @Test
    void theInsertPositionEnumStaysConstrainedInTheSchema() {
        // The lenient @JsonCreator on InsertPosition must not cost the model the list of accepted
        // values in the tool schema — that list is what stops it guessing in the first place.
        assertThat(toolNamed("insertDocumentSection").getToolDefinition().inputSchema())
                .contains("BEFORE")
                .contains("AFTER");
    }

    private static ToolCallback toolNamed(String name) {
        for (ToolCallback tool : allTools()) {
            if (tool.getToolDefinition().name().equals(name)) {
                return tool;
            }
        }
        throw new IllegalStateException("no such tool: " + name);
    }

    /** The {@code required} array of the tool's generated JSON schema. */
    private static List<String> requiredParams(ToolCallback tool) {
        try {
            final JsonNode required =
                    MAPPER.readTree(tool.getToolDefinition().inputSchema()).path("required");
            final List<String> names = new ArrayList<>();
            required.forEach(node -> names.add(node.asText()));
            return names;
        } catch (Exception e) {
            throw new IllegalStateException("unreadable schema for " + tool, e);
        }
    }

    private static Throwable rootCause(Throwable e) {
        return e.getCause() == null ? e : rootCause(e.getCause());
    }
}
