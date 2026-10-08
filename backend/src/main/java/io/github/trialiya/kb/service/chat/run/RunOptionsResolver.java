package io.github.trialiya.kb.service.chat.run;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ReasoningOptions;
import io.github.trialiya.kb.model.chat.dto.RunChoice;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.project.ProjectSwitch;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.prompt.ChatModeService;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * «Что выбрано в этом чате» → {@link ChatRunService.RunOptions}: модель, режим, уровень
 * рассуждений и проект прогона, собранные из параметров запроса поверх памяти чата ({@code
 * chat_topic}). Одно место на все пути генерации — синхронный, фоновый и автостарт по очереди, —
 * чтобы выбор решался для них одинаково.
 *
 * <p>Сервис, а не приватный метод контроллера: прогон, который запускается по накопившейся очереди
 * сообщений (см. {@link ChatRunService}), никакого HTTP-запроса под собой не имеет, а настройки ему
 * нужны те же самые.
 *
 * <p>Резолв — не чистая функция: он же и записывает выбор в {@code chat_topic}. Названные явно
 * модель, режим и уровень рассуждений запоминаются как «последние», а проект приводится к тому,
 * на чём прогон реально пошёл (см. {@link #resolve}).
 */
@Service
public class RunOptionsResolver {

    private final ChatModelProperties chatModelProperties;
    private final ChatModeProperties chatModeProperties;
    private final ChatModeService chatModeService;
    private final ChatTopicRepository chatTopicRepository;
    private final ProjectCatalog projectCatalog;

    public RunOptionsResolver(
            ChatModelProperties chatModelProperties,
            ChatModeProperties chatModeProperties,
            ChatModeService chatModeService,
            ChatTopicRepository chatTopicRepository,
            ProjectCatalog projectCatalog) {
        this.chatModelProperties = chatModelProperties;
        this.chatModeProperties = chatModeProperties;
        this.chatModeService = chatModeService;
        this.chatTopicRepository = chatTopicRepository;
        this.projectCatalog = projectCatalog;
    }

    /**
     * Настройки одного прогона. {@code null} в любом поле {@code choice} означает «не названо» —
     * так же, как отсутствующее поле запроса: решает память чата, а за ней конфигурация.
     */
    public ChatRunService.RunOptions resolve(final String conversationId, final RunChoice choice) {
        // Одна строка chat_topic на все разрешения (отдельными чтениями это был бы один и тот же
        // SELECT по разу на каждое). Проекту она нужна даже при пришедшем параметре: прежнее
        // значение колонки — это «на каком проекте шла история», и сравнение с ним даёт маркер
        // смены проекта.
        final Optional<ChatTopicEntity> stored = chatTopicRepository.findById(conversationId);
        final String resolvedModel = resolveModel(conversationId, stored, choice.model());
        final String previousProject = stored.map(ChatTopicEntity::getProject).orElse(null);
        final String resolvedProject = resolveProject(stored, choice.project());
        final ProjectSwitch switched = projectSwitch(previousProject, resolvedProject);
        if (!Objects.equals(previousProject, resolvedProject)) {
            // Колонку приводим к тому, на чём прогон реально пошёл, — и когда проект назвали, и
            // когда сохранённый выбыл из конфигурации и выродился в дефолтный. Второе не записать
            // нельзя: следующее сообщение сравнилось бы с тем же выбывшим значением и повторило
            // маркер, которому место ровно на одном вопросе — том, которым история сменила
            // репозиторий.
            chatTopicRepository.updateProject(conversationId, resolvedProject);
        }
        return new ChatRunService.RunOptions(
                resolvedModel,
                chatModelProperties.isWeak(resolvedModel),
                chatModelProperties.streamUsage(resolvedModel),
                chatModeService.instructionsFor(resolveMode(conversationId, stored, choice.mode())),
                resolveReasoning(conversationId, stored, choice.reasoning(), resolvedModel),
                resolvedProject,
                projectCatalog.require(resolvedProject).id(),
                switched);
    }

    /**
     * Настройки, на которых чат идёт сейчас, — то же, что вернул бы {@link #resolve} без единого
     * названного параметра, но <b>ничего не записывая</b>. Для операции, которая обращается к
     * модели от имени чата, но ходом чата не является: сжатие контекста ({@code CompactService}).
     *
     * <p>Разница не косметическая. {@link #resolve} приводит {@code chat_topic.project} к тому, на
     * чём прогон реально пошёл, и заодно вычисляет маркер смены — а операции этот маркер девать
     * некуда. Позови она {@code resolve}, чат с выбывшим из конфигурации проектом переписал бы
     * колонку на дефолтную молча, и следующий вопрос пользователя остался бы без {@code
     * projectSwitchFrom}: ни модель, ни плашка не узнали бы, что история выше писалась в другом
     * репозитории.
     *
     * <p>{@code projectSwitch} здесь всегда {@code null}, и это правда: операция чат никуда не
     * переводит.
     */
    public ChatRunService.RunOptions current(final String conversationId) {
        // Ни один из резолвов не пишет, пока ему не назвали значение явно (см. их javadoc).
        final Optional<ChatTopicEntity> stored = chatTopicRepository.findById(conversationId);
        final String model = resolveModel(conversationId, stored, null);
        final String project = resolveProject(stored, null);
        return new ChatRunService.RunOptions(
                model,
                chatModelProperties.isWeak(model),
                chatModelProperties.streamUsage(model),
                chatModeService.instructionsFor(resolveMode(conversationId, stored, null)),
                resolveReasoning(conversationId, stored, null, model),
                project,
                projectCatalog.require(project).id(),
                null);
    }

    /**
     * Проверяет, что названные модель, режим, уровень рассуждений и проект вообще существуют, ничего не резолвя и ничего
     * не записывая. Нужно там, где выбор принимают сейчас, а прогон по нему пойдёт позже (очередь
     * сообщений): без этой проверки опечатка в модели обернулась бы не отказом на запросе, а
     * сообщением, которое приняли и на которое потом молча не ответили.
     */
    public void validate(final RunChoice choice) {
        final String model = choice.model();
        final String mode = choice.mode();
        final String reasoning = choice.reasoning();
        final String project = choice.project();
        if (StringUtils.hasText(model) && !chatModelProperties.isAllowed(model)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown model: " + model);
        }
        if (StringUtils.hasText(mode) && !chatModeProperties.isAllowed(mode)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown mode: " + mode);
        }
        if (StringUtils.hasText(reasoning) && !chatModelProperties.isKnownReasoningLevel(reasoning)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown reasoning level: " + reasoning);
        }
        if (StringUtils.hasText(project) && !projectCatalog.isAllowed(project)) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown project: " + project);
        }
    }

    /**
     * Параметр запроса → сохранённая модель чата → null. {@code null} означает «не переопределять»,
     * т.е. едем на модели из application.yaml.
     *
     * @param stored строка чата, если её уже прочитали; пустая — параметр запроса всё решает сам
     */
    public @Nullable String resolveModel(
            final String conversationId, final Optional<ChatTopicEntity> stored, final @Nullable String requested) {
        if (StringUtils.hasText(requested)) {
            if (!chatModelProperties.isAllowed(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown model: " + requested);
            }
            chatTopicRepository.updateModel(conversationId, requested); // запоминаем как «последнюю»
            return requested;
        }
        return stored.map(ChatTopicEntity::getModel)
                .filter(StringUtils::hasText)
                .filter(chatModelProperties::isAllowed) // на случай, если модель убрали из конфига
                .orElse(null);
    }

    /**
     * Параметр запроса → сохранённый режим чата → null. {@code null} означает «без режима»
     * (плейсхолдер {@code mode_instructions} заполняется пустой строкой). Параллель {@link
     * #resolveModel}.
     */
    private @Nullable String resolveMode(
            final String conversationId, final Optional<ChatTopicEntity> stored, final @Nullable String requested) {
        if (StringUtils.hasText(requested)) {
            if (!chatModeProperties.isAllowed(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown mode: " + requested);
            }
            chatTopicRepository.updateMode(conversationId, requested); // запоминаем как «последний»
            return requested;
        }
        return stored.map(ChatTopicEntity::getMode)
                .filter(StringUtils::hasText)
                .filter(chatModeProperties::isAllowed) // на случай, если режим убрали из конфига
                .orElse(null);
    }

    /**
     * Параметр запроса → сохранённый уровень чата → уровень модели прогона. Записывается, как и
     * режим, только названный явно — и записывается как назван, даже если у модели этого прогона
     * его нет: выбор принадлежит чату, а модель в нём переключают, и вернувшись на модель с таким
     * уровнем, чат обязан снова на нём пойти. Сам прогон при этом едет на том, что у модели есть
     * (см. {@code ChatModelProperties#reasoningLevel}).
     *
     * <p>Уровень, которого нет ни у одной модели, — опечатка, а не переключение: 400, как у
     * неизвестных модели и режима. Сохранённый же уровень, выбывший из конфигурации, тихо уступает
     * умолчанию модели.
     *
     * @param model модель прогона, уже разрешённая ({@code null} — модель по умолчанию)
     * @return {@code null} — отправлять нечего: у модели нет уровней, или ни выбора, ни умолчания
     */
    private ReasoningOptions.@Nullable Level resolveReasoning(
            final String conversationId,
            final Optional<ChatTopicEntity> stored,
            final @Nullable String requested,
            final @Nullable String model) {
        final String chosen;
        if (StringUtils.hasText(requested)) {
            if (!chatModelProperties.isKnownReasoningLevel(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown reasoning level: " + requested);
            }
            chatTopicRepository.updateReasoning(conversationId, requested); // запоминаем как «последний»
            chosen = requested;
        } else {
            chosen = stored.map(ChatTopicEntity::getReasoning).orElse(null);
        }
        return chatModelProperties.reasoningLevel(model, chosen).orElse(null);
    }

    /**
     * Параметр запроса → сохранённый проект чата → null. {@code null} означает «проект не назван»:
     * инструменты прогона поедут на первом проекте списка (см. {@code ProjectCatalog}). Параллель
     * {@link #resolveModel}.
     *
     * <p>В отличие от модели и режима, колонку пишет не этот метод, а {@link #resolve} — одной
     * записью «привести к тому, на чём прогон реально пошёл». Ответ здесь бывает не тем, что
     * сохранено (выбывший из конфигурации проект вырождается в дефолтный), и записать надо именно
     * ответ: {@code chat_topic.project} означает «на каком проекте чат реально работал», а не «что
     * выбрано в селекторе». Выбор, не подтверждённый отправкой, живёт на фронте; поэтому же
     * сравнение с прежним значением колонки (см. {@link #projectSwitch}) и есть детекция настоящей
     * смены проекта.
     */
    private @Nullable String resolveProject(final Optional<ChatTopicEntity> stored, final @Nullable String requested) {
        if (StringUtils.hasText(requested)) {
            if (!projectCatalog.isAllowed(requested)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown project: " + requested);
            }
            return requested;
        }
        return stored.map(ChatTopicEntity::getProject)
                .filter(StringUtils::hasText)
                .filter(projectCatalog::isAllowed) // на случай, если проект убрали из конфига
                .orElse(null);
    }

    /**
     * Смена проекта относительно того, на котором чат работал до этого сообщения. Сравнение — по
     * каноническим id: не названный проект и явно названный дефолтный означают один репозиторий.
     * Проект, выбывший из конфигурации, канонизировать не во что — его id сравнивается как есть, и
     * переезд с него на дефолтный тоже смена: история-то читана в другом репозитории.
     */
    private @Nullable ProjectSwitch projectSwitch(@Nullable final String previous, @Nullable final String resolved) {
        final String to = projectCatalog.require(resolved).id();
        final String from = previous == null
                ? projectCatalog.defaultProject().id()
                : projectCatalog.find(previous).map(Project::id).orElse(previous);
        return to.equals(from) ? null : new ProjectSwitch(from, to);
    }
}
