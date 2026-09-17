package io.github.trialiya.kb.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.trialiya.kb.model.git.dto.GitFileBytes;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
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

    @Test
    void aMissingFileIsABadRequestAndNotAServerError() throws Exception {
        when(git.getFileContent("gone.md", null, null))
                .thenThrow(new IllegalArgumentException("File not found: gone.md"));

        mockMvc.perform(get("/api/git/files/content").param("path", "gone.md"))
                .andExpect(status().isBadRequest());
    }

    /** Ревизия приходит из поля ввода, и опечатка в ней — такая же ошибка запроса. */
    @Test
    void anUnknownRevisionIsABadRequest() throws Exception {
        when(git.getFileContentAt("nosuchtag", "README.md", null, null))
                .thenThrow(new IllegalArgumentException("Commit not found: nosuchtag"));

        mockMvc.perform(
                        get("/api/git/files/content")
                                .param("path", "README.md")
                                .param("rev", "nosuchtag"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Картинка отдаётся с типом, взятым по расширению, — и без права на что-либо активное внутри:
     * SVG умеет и скрипты, и открыть такой ответ можно прямым переходом, а не только из {@code
     * <img>}.
     */
    @Test
    void anImageIsServedRawWithItsOwnTypeAndNothingActiveAllowed() throws Exception {
        byte[] bytes = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(UTF_8);
        when(git.getRawFile("docs/icon.svg"))
                .thenReturn(new GitFileBytes("docs/icon.svg", bytes, 0, bytes.length, false));

        mockMvc.perform(get("/api/git/files/raw").param("path", "docs/icon.svg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(
                        header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
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

    /** Слишком большой файл — отказ запроса, а не куча памяти: см. GitService.getRawFile. */
    @Test
    void anImageTooLargeToServeIsABadRequest() throws Exception {
        when(git.getRawFile("huge.png"))
                .thenThrow(new IllegalArgumentException("File is too large to preview"));

        mockMvc.perform(get("/api/git/files/raw").param("path", "huge.png"))
                .andExpect(status().isBadRequest());
    }

    /** Список изменений сужают тем же путём, что и открывают файл, — и ошибаются в нём так же. */
    @Test
    void aStatusRequestForAnImpossiblePathIsABadRequest() throws Exception {
        when(git.getUncommittedChanges(false, "docs/ab.md"))
                .thenThrow(new IllegalArgumentException("Path contains unsupported characters"));

        mockMvc.perform(get("/api/git/status").param("path", "docs/ab.md"))
                .andExpect(status().isBadRequest());
    }
}
