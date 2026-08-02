package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

/**
 * What happens when a weak model calls a tool and fills in nothing at all.
 *
 * <p>Spring AI hands an absent argument to {@code Method.invoke} as {@code null}. For a wrapper
 * type that is harmless — the tool body sees it and answers. For a <b>primitive</b> parameter
 * reflection cannot unbox it and throws {@code IllegalArgumentException} from {@code invoke}
 * itself, which is therefore <em>not</em> wrapped in {@link ToolExecutionException}, never reaches
 * the {@code ToolExecutionExceptionProcessor}, and kills the whole chat run instead of one call.
 *
 * <p>So the invariant pinned here is not "every tool validates" but the stronger, mechanical one:
 * <b>an argument-less call is always answerable</b>. Whatever a tool decides — default it, or
 * refuse it — the failure has to arrive through the framework's error channel, naming an argument
 * the model can go and fill in. Which is the whole rule for writing a new {@code @Tool}:
 *
 * <ul>
 *   <li><b>No primitive parameters.</b> Declare {@code Long}/{@code Integer}/{@code Boolean} so a
 *       gap arrives as {@code null} and the tool body can answer it.
 *   <li><b>Answer the gap on purpose</b>, through {@code ToolArgs} (in {@code kb.tools}): {@code
 *       orDefault} / {@code positiveOrDefault} where the default is genuinely what the caller meant
 *       (limits, modes, flags — {@code required = false} in the schema), {@code require*} where
 *       nothing could stand in for it (ids, paths, the content being written).
 * </ul>
 *
 * <p>Nothing has to be remembered for this to hold: the tools are found by scanning, so a new one
 * is covered the day it is written, and both halves of the rule fail the build if broken.
 */
class ToolArgumentGapsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Where the chat model's tools live; everything in here is scanned. */
    private static final String FUNCTIONS_PACKAGE = "io.github.trialiya.kb.functions";

    private final ToolContext context =
            new ToolContext(Map.of(ChatMemory.CONVERSATION_ID, "test-chat"));

    /** Every {@code @Tool} in {@link #FUNCTIONS_PACKAGE}, over mocked services. */
    private static ToolCallback[] allTools() {
        return ToolCallbacks.from(
                toolClasses().map(ToolArgumentGapsTest::withMockedDeps).toArray());
    }

    /** The tool-holding classes on the classpath — found, not listed. */
    private static Stream<Class<?>> toolClasses() {
        final ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        return scanner.findCandidateComponents(FUNCTIONS_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .<Class<?>>map(name -> ClassUtils.resolveClassName(name, null))
                .filter(
                        type ->
                                Stream.of(type.getDeclaredMethods())
                                        .anyMatch(m -> m.isAnnotationPresent(Tool.class)))
                .sorted(Comparator.comparing(Class::getName));
    }

    /**
     * Builds a tool holder through its widest constructor, mocking whatever it asks for. Keeps the
     * scan honest: a tool class that grows a new dependency needs no edit here.
     */
    private static Object withMockedDeps(Class<?> type) {
        final Constructor<?> constructor =
                Stream.of(type.getDeclaredConstructors())
                        .max(Comparator.comparingInt(Constructor::getParameterCount))
                        .orElseThrow(() -> new IllegalStateException("no constructor on " + type));
        constructor.setAccessible(true);
        final Object[] dependencies =
                Stream.of(constructor.getParameterTypes())
                        .map(ToolArgumentGapsTest::stubbed)
                        .toArray();
        try {
            return constructor.newInstance(dependencies);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build " + type.getSimpleName(), e);
        }
    }

    private static Object stubbed(Class<?> dependency) {
        if (!dependency.isPrimitive()) {
            return mock(dependency);
        }
        // A constructor flag, not an argument under test: ScriptFunction's read-only switch, off,
        // because the full tool set is the one worth checking.
        return switch (dependency.getSimpleName()) {
            case "boolean" -> false;
            case "int" -> 0;
            case "long" -> 0L;
            default ->
                    throw new IllegalStateException(
                            "unhandled constructor primitive: " + dependency);
        };
    }

    @Test
    void theScanActuallyFindsTheTools() {
        // Without this the whole class could pass by finding nothing at all.
        assertThat(allTools())
                .describedAs("@Tool callbacks discovered in " + FUNCTIONS_PACKAGE)
                .hasSizeGreaterThan(20);
        assertThat(toolClasses().map(Class::getSimpleName))
                .contains("DocumentFunction", "GitFunction", "ScriptFunction");
    }

    @Test
    void noToolCanBringDownTheRunByBeingCalledWithNoArguments() {
        final List<String> broken = new ArrayList<>();
        for (ToolCallback tool : allTools()) {
            final String name = tool.getToolDefinition().name();
            try {
                tool.call("{}", context);
            } catch (ToolExecutionException ignored) {
                // The good failure: wrapped, so the processor turns it into a tool result the
                // model reads and retries from.
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

    /**
     * The reason the no-primitives rule exists, kept executable rather than remembered.
     *
     * <p>This tool is registered nowhere — it is the mistake, on purpose. As long as Spring AI
     * behaves as described above, the missing {@code boolean} escapes reflection as a bare {@code
     * IllegalArgumentException} and this passes. The day a Spring AI upgrade fills the primitive
     * in, or at least routes the failure through {@link ToolExecutionException}, this test fails
     * and says so — the rule can then be relaxed instead of being carried forever out of habit.
     *
     * <p>Only the exception type is asserted: the wording is the JDK's, and it has already changed
     * once ("argument type mismatch" on 21, a null-conversion {@code NullPointerException} on 25),
     * so pinning it would only break the Java 21 fallback build.
     *
     * <p>Filed upstream as <a
     * href="https://github.com/spring-projects/spring-ai/issues/6723">spring-ai#6723</a>. Not the
     * same bug as the two related issues we checked before filing: <a
     * href="https://github.com/spring-projects/spring-ai/pull/5032">PR #5032</a> (merged, GH-3924)
     * fixes invalid-enum conversion, and <a
     * href="https://github.com/spring-projects/spring-ai/pull/6018">PR #6018</a> (open, GH-3884)
     * guards blank-string-to-number conversion — both act inside {@code buildTypedArgument}'s
     * {@code try}, which a missing primitive never reaches because the {@code value == null} check
     * returns before it. Once #6723 is resolved, revisit this test and the no-primitives rule.
     */
    @Test
    void springAiStillCannotFillInAMissingPrimitive() {
        final ToolCallback tool = ToolCallbacks.from(new PrimitiveArgumentTool())[0];
        try {
            final String answered = tool.call("{}", context);
            fail(
                    "Spring AI now supplies a missing primitive by itself (answered "
                            + answered
                            + "): a primitive no longer kills the run, so the no-primitives rule in"
                            + " this class can be revisited.");
        } catch (ToolExecutionException e) {
            fail(
                    "Spring AI now wraps the null primitive ("
                            + rootCause(e).getMessage()
                            + "): the failure reaches the exception processor and the run survives,"
                            + " so the no-primitives rule can be revisited.");
        } catch (IllegalArgumentException expected) {
            assertThat(expected)
                    .describedAs("thrown by Method.invoke itself, past every Spring AI handler")
                    .isNotInstanceOf(ToolExecutionException.class);
        }
    }

    /**
     * Not wired into any chat: it exists only to demonstrate the failure mode above. It lives here
     * rather than in {@link #FUNCTIONS_PACKAGE} because that package is scanned — by this test, and
     * by {@code ToolTranslationsTest}, which would then demand a UI label for it.
     */
    static class PrimitiveArgumentTool {

        @Tool(description = "Test-only tool with the signature the codebase forbids.")
        String primitiveFlag(boolean flag) {
            return String.valueOf(flag);
        }
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
