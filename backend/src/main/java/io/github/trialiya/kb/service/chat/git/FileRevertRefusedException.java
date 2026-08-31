package io.github.trialiya.kb.service.chat.git;

/**
 * Откат файловых правок не состоялся, и рабочее дерево осталось нетронутым: откатывать нечего,
 * ответ менял файлы способом, который откат отменить не умеет, или файл с тех пор изменился.
 *
 * <p>Сообщение пишется для человека и уходит в ответ эндпоинта как есть — как и у {@code
 * GitCommandFailedException}, из которого пользователь узнаёт, что именно git отказался делать.
 */
public class FileRevertRefusedException extends RuntimeException {

    public FileRevertRefusedException(String message) {
        super(message);
    }
}
