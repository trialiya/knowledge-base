package io.github.trialiya.kb.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import io.github.trialiya.kb.model.git.dto.GitFileBytes;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.git.dto.GitSymbol;
import io.github.trialiya.kb.service.file.git.GitReadTimeoutException;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Коды ответов на чтение того, чего в репозитории нет.
 *
 * <p>Назвать несуществующее может кто угодно: устаревший чип на удалённый файл, ссылка из чата,
 * опечатка в поле ревизии. {@link GitService} отвечает на это {@link IllegalArgumentException}, и
 * ответить на него «внутренней ошибкой» значило бы свалить вину на сервер, который здоров, — а ещё
 * записать в лог ERROR-стектрейс на каждый такой запрос.
 *
 * <p>Стенд собран standalone: проверяются коды самого контроллера, а не конфигурация приложения
 * вокруг него.
 */
class GitControllerTest {

    private final GitService git = mock(GitService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GitRegistry registry = mock(GitRegistry.class);
        when(registry.forProject(any())).thenReturn(git);
        mockMvc = MockMvcBuilders.standaloneSetup(new GitController(registry)).build();
    }

    /**
     * Каждое чтение идёт через {@code read()}: всё, что {@link GitService} отвергает {@link
     * IllegalArgumentException} из-за запроса клиента, — 400 на любом эндпоинте.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedReads")
    void whatGitRefusesForTheRequestIsABadRequest(
            String what, Consumer<GitService> stub, MockHttpServletRequestBuilder request) throws Exception {
        stub.accept(git);

        mockMvc.perform(request).andExpect(status().isBadRequest());
    }

    static Stream<Arguments> refusedReads() {
        return Stream.of(
                Arguments.of(
                        "missing file",
                        (Consumer<GitService>) g -> when(g.getFileContent(null, "gone.md", null, null))
                                .thenThrow(new IllegalArgumentException("File not found: gone.md")),
                        get("/api/git/files/content").param("path", "gone.md")),
                Arguments.of(
                        "unknown revision of a file",
                        (Consumer<GitService>) g -> when(g.getFileContent("nosuchtag", "README.md", null, null))
                                .thenThrow(new IllegalArgumentException("Commit not found: nosuchtag")),
                        get("/api/git/files/content").param("path", "README.md").param("rev", "nosuchtag")),
                Arguments.of(
                        "outline of an unsupported language",
                        (Consumer<GitService>) g -> when(g.getFileOutline(null, "notes.txt"))
                                .thenThrow(new IllegalArgumentException("Unsupported language for outline")),
                        get("/api/git/files/outline").param("path", "notes.txt")),
                Arguments.of(
                        "unknown commit",
                        (Consumer<GitService>) g -> when(g.getCommit("nosuchtag", false, null))
                                .thenThrow(new IllegalArgumentException("Commit not found: nosuchtag")),
                        get("/api/git/commit").param("rev", "nosuchtag")),
                Arguments.of(
                        "commit search from an unknown revision",
                        (Consumer<GitService>) g -> when(g.grepCommits("fix", 50, "nosuchtag"))
                                .thenThrow(new IllegalArgumentException("Commit not found: nosuchtag")),
                        get("/api/git/commits/grep")
                                .param("q", "fix")
                                .param("limit", "50")
                                .param("rev", "nosuchtag")),
                // Отказ, а не куча памяти: см. GitService.getRawFile.
                Arguments.of(
                        "image too large to serve",
                        (Consumer<GitService>) g -> when(g.getRawFile(null, "huge.png"))
                                .thenThrow(new IllegalArgumentException("File is too large to preview")),
                        get("/api/git/files/raw").param("path", "huge.png")),
                Arguments.of(
                        "status narrowed to an impossible path",
                        (Consumer<GitService>) g -> when(g.getUncommittedChanges(false, "docs/a\u0007b.md"))
                                .thenThrow(new IllegalArgumentException("Path contains unsupported characters")),
                        get("/api/git/status").param("path", "docs/a\u0007b.md")));
    }

    /** Обзор с ревизией читает снимок, без неё — рабочее дерево. */
    @Test
    void anOutlineIsReadFromTheRevisionWhenOneIsNamed() throws Exception {
        when(git.getFileOutline("v1", "README.md"))
                .thenReturn(new GitFileOutline(
                        "README.md",
                        true,
                        "markdown",
                        2,
                        "markdown",
                        List.of(new GitSymbol("h1", "Title", "Title", 1, 2))));

        mockMvc.perform(get("/api/git/files/outline").param("path", "README.md").param("rev", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbols[0].signature").value("Title"))
                .andExpect(jsonPath("$.symbols[0].startLine").value(1));
    }

    /** Blame с ревизией читает снимок; ханки уезжают как есть, с полным хешем и коротким. */
    @Test
    void blameIsReadFromTheRevisionWhenOneIsNamed() throws Exception {
        String hash = "a".repeat(40);
        when(git.getBlame("v1", "README.md"))
                .thenReturn(new GitFileBlame(
                        "README.md",
                        hash,
                        2,
                        List.of(new GitFileBlame.Hunk(
                                1,
                                2,
                                hash,
                                "Alice",
                                OffsetDateTime.parse("2024-01-02T03:04:05+03:00"),
                                "first",
                                "README.md",
                                1)),
                        null,
                        null));

        mockMvc.perform(get("/api/git/files/blame").param("path", "README.md").param("rev", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commit").value(hash))
                .andExpect(jsonPath("$.hunks[0].hash").value(hash))
                .andExpect(jsonPath("$.hunks[0].lineCount").value(2))
                // Плашка инструмента модели — не поле ответа; диапазона не спрашивали — его нет.
                .andExpect(jsonPath("$.formattedResponse").doesNotExist())
                .andExpect(jsonPath("$.fromLine").doesNotExist());
    }

    /** Файл без истории — ошибка запроса; blame, не уложившийся в дедлайн, — 503, как у grep. */
    @Test
    void blameOfAnUntrackedFileIsABadRequestAndATimeoutIsUnavailable() throws Exception {
        when(git.getBlame(null, "new.txt")).thenThrow(new IllegalArgumentException("File is not tracked: new.txt"));
        when(git.getBlame(null, "slow.txt")).thenThrow(new GitReadTimeoutException("git blame did not finish"));

        mockMvc.perform(get("/api/git/files/blame").param("path", "new.txt")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/git/files/blame").param("path", "slow.txt"))
                .andExpect(status().isServiceUnavailable());
    }

    /**
     * Происхождение подстроки: ревизия и строка уходят в сервис, шаги — как есть; ошибка вызова —
     * 400, обход, не уложившийся в срок, — 503, как у blame.
     */
    @Test
    void lineOriginIsReadAtTheRevisionAndItsFailuresMapLikeBlame() throws Exception {
        String hash = "a".repeat(40);
        when(git.getLineOrigin("v1", "A.java", 12, "total"))
                .thenReturn(new GitLineOrigin(
                        "A.java",
                        12,
                        "total",
                        hash,
                        GitLineOrigin.Status.FOUND,
                        List.of(new GitLineOrigin.Step(hash, "Alice", null, "first", "A.java", 10, "int total;")),
                        null));
        when(git.getLineOrigin(null, "A.java", 0, "x")).thenThrow(new IllegalArgumentException("line must be 1"));
        when(git.getLineOrigin(null, "slow.txt", 1, "x")).thenThrow(new GitReadTimeoutException("too slow"));

        mockMvc.perform(get("/api/git/files/origin")
                        .param("path", "A.java")
                        .param("line", "12")
                        .param("query", "total")
                        .param("rev", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.steps[0].line").value(10))
                .andExpect(jsonPath("$.before").doesNotExist());
        mockMvc.perform(get("/api/git/files/origin")
                        .param("path", "A.java")
                        .param("line", "0")
                        .param("query", "x"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/git/files/origin")
                        .param("path", "slow.txt")
                        .param("line", "1")
                        .param("query", "x"))
                .andExpect(status().isServiceUnavailable());
    }

    /** Поиск, который git не закончил в срок, — тот же 503, что у blame: оба идут через read(). */
    @Test
    void aGrepTimeoutIsUnavailable() throws Exception {
        when(git.grepPage("slow", null, false, null, false, 200))
                .thenThrow(new GitReadTimeoutException("git grep did not finish"));

        mockMvc.perform(get("/api/git/grep").param("q", "slow")).andExpect(status().isServiceUnavailable());
    }

    /** Коммит без ревизии не назван вовсе: «рабочее дерево» у этого запроса не ответ. */
    @Test
    void aCommitWithoutRevisionIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/git/commit").param("rev", " ")).andExpect(status().isBadRequest());
    }

    /**
     * Картинка отдаётся с типом, взятым по расширению, — и без права на что-либо активное внутри:
     * SVG умеет и скрипты, и открыть такой ответ можно прямым переходом, а не только из {@code
     * <img>}.
     */
    @Test
    void anImageIsServedRawWithItsOwnTypeAndNothingActiveAllowed() throws Exception {
        byte[] bytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(UTF_8);
        when(git.getRawFile(null, "docs/icon.svg"))
                .thenReturn(new GitFileBytes("docs/icon.svg", bytes, 0, bytes.length, false));

        mockMvc.perform(get("/api/git/files/raw").param("path", "docs/icon.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
    }

    /**
     * Сырыми отдаются только те расширения, чей тип содержимого известен списку: иначе эндпоинт был
     * бы способом отдать со своего origin что угодно с угаданным типом.
     */
    @Test
    void aFileThatIsNotPreviewableIsRefusedRatherThanGuessed() throws Exception {
        mockMvc.perform(get("/api/git/files/raw").param("path", "index.html"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
