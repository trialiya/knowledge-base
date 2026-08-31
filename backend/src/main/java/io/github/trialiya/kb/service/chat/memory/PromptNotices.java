package io.github.trialiya.kb.service.chat.memory;

import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.FileRevertMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import org.jspecify.annotations.Nullable;

/**
 * Служебные строки, которые история дописывает к сообщению пользователя при чтении: маркер смены
 * проекта, пометка вопроса-вклинивания, след git-команды и след отката файловых правок.
 *
 * <p>Собираются на чтении, а не пишутся в БД: то, какими словами разговаривать с моделью, меняется
 * вместе с промптами, а история — нет. Отсюда же требование «сохраняй дословно» в каждом нотисе,
 * который должен пережить сжатие: его вход строится из тех же {@code PromptRow} (правила
 * продублированы в {@code prompt/summarizer.md}).
 *
 * <p>Отдельно от {@link ChatHistoryService}, потому что это тексты, а не работа с историей: они
 * ничего не читают и не пишут, и меняются по своему поводу — от того, что модель поняла нотис
 * неправильно, а не от того, что поехали ряды.
 */
public final class PromptNotices {

    private PromptNotices() {}

    /**
     * Текст маркера смены проекта для модели. Требование «сохраняй дословно» адресовано
     * summarizer'у: его вход строится из этих же {@code PromptRow}, и потерянный при сжатии маркер
     * снова сделал бы раннюю историю «актуальной» (правило продублировано в {@code summarizer.md}).
     */
    static String projectSwitchNotice(@Nullable ChatMessageMeta meta) {
        if (meta == null || meta.projectSwitchFrom() == null) {
            return "";
        }
        return "<project-switched from=\""
                + meta.projectSwitchFrom()
                + "\" to=\""
                + meta.project()
                + "\">\n"
                + "The user switched this chat to another project at this message. Everything"
                + " earlier in the conversation — file paths, file contents, grep and script"
                + " results — belongs to project \""
                + meta.projectSwitchFrom()
                + "\"; do not assume any of it exists or looks the same in \""
                + meta.project()
                + "\". Re-read whatever you need with the tools. When summarizing, preserve this"
                + " notice verbatim.\n"
                + "</project-switched>\n\n";
    }

    /**
     * Текст нотиса вопроса, доставленного посреди прогона. В отличие от маркера смены проекта, у
     * summarizer'а здесь ПРОТИВОПОЛОЖНАЯ инструкция — свернуть как обычную реплику, а тег не
     * сохранять: после завершения хода сообщение ничем не отличается от прочих просьб пользователя,
     * и дословная обёртка только копила бы служебный текст в каждой сводке. Формулировка нейтральна
     * к моменту чтения — тот же текст верен и внутри живой итерации, и в любом последующем прогоне.
     */
    static String interjectionNotice(@Nullable ChatMessageMeta meta) {
        if (meta == null || !meta.interjection()) {
            return "";
        }
        return "<user-interjection>\n"
                + "The user sent this message while you were still working on the previous"
                + " request — they were reacting to your progress, not to a finished answer. Take"
                + " it into account before continuing: it may redirect, narrow, or add to the"
                + " task. When summarizing, fold its content into the user's requests as an"
                + " ordinary message; this tag itself need not be preserved.\n"
                + "</user-interjection>\n\n";
    }

    /**
     * Текст ряда git-команды для модели. Вывод самого git сюда не идёт: модели нужно знать, что
     * репозиторий сдвинулся и куда, а не читать «Fast-forward» построчно — вывод для человека и
     * лежит там, где человек его открывает.
     *
     * <p>Отказ рассказывается наравне с успехом, и он важнее: после отклонённого push ветка
     * осталась там же, где была, и модель, решившая иначе, будет строить работу на несуществующем
     * состоянии. Требование «сохраняй дословно», как и у маркера смены проекта, адресовано
     * summarizer'у (правило продублировано в {@code prompt/summarizer.md}).
     */
    public static String gitCommandNotice(@Nullable ChatMessageMeta meta) {
        if (meta == null || meta.gitEvent() == null) {
            return "";
        }
        final GitEventMeta event = meta.gitEvent();
        return "<git-command command=\""
                + attr(event.command())
                + "\" outcome=\""
                + (event.ok() ? "ok" : "refused")
                + "\""
                + (event.project() == null ? "" : " project=\"" + attr(event.project()) + "\"")
                + (event.branch() == null ? "" : " branch=\"" + attr(event.branch()) + "\"")
                + ">\n"
                + "The user ran this git command on the project from this chat — not you, and not"
                + " through any tool of yours. "
                + (event.ok()
                        ? "It succeeded: the working tree may differ from what you read earlier, so"
                                + " re-read with the tools anything you are about to rely on."
                        : "It was refused, so the repository is where it was before — do not treat"
                                + " the command as done.")
                + " When summarizing, preserve this notice verbatim.\n"
                + "</git-command>\n";
    }

    /**
     * Текст ряда отката файловых правок. Модели важно ровно одно: файлы, которые она только что
     * написала, вернулись к прежнему состоянию, — поэтому список путей идёт целиком, а «чем именно
     * их вернули» не идёт вовсе.
     *
     * <p>Отдельная просьба не переделывать правку молча: без неё «файлы откачены» читается как
     * поломка, которую надо чинить, и следующий же ход возвращает ровно то, что человек убрал.
     * Требование «сохраняй дословно» — как у нотисов выше.
     */
    static String fileRevertNotice(@Nullable ChatMessageMeta meta) {
        if (meta == null || meta.fileRevert() == null) {
            return "";
        }
        final FileRevertMeta revert = meta.fileRevert();
        return "<files-reverted"
                + (revert.project() == null ? "" : " project=\"" + attr(revert.project()) + "\"")
                + ">\n"
                + "The user reverted the file changes from your previous answer: "
                + attr(String.join(", ", revert.paths()))
                + ". Those files are back to the state they had before that answer — your edits to"
                + " them are gone, so do not rely on anything you wrote there and re-read what you"
                + " need with the tools. Do not redo the reverted work unless the user asks: the"
                + " revert is their decision about the change, not a failure to fix. When"
                + " summarizing, preserve this notice verbatim.\n"
                + "</files-reverted>\n";
    }

    /**
     * Значение атрибута нотиса. Имена веток и пути — единственное место, где текст извне попадает в
     * разметку, которую читает модель, а git запрещает в них далеко не всё: ни кавычка, ни угловые
     * скобки под запрет не попадают. Кавычкой закрывают атрибут, угловой скобкой — сам тег, и
     * ветка, названная {@code main>...</git-command}, дописала бы модели произвольный текст поверх
     * нотиса. Вывод команды такой поверхностью не является: он модели не показывается вовсе.
     */
    private static String attr(String value) {
        return value.replace("\"", "'").replace("<", "‹").replace(">", "›");
    }
}
