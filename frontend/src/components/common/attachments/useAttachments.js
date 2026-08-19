import { useCallback, useEffect, useRef, useState } from 'react';
import attachmentApi from '@/api/attachmentApi';

/**
 * Вложения одного владельца (чат или документ): список и операции над ним.
 * Компоненту остаётся рендер — состояние и походы в API живут здесь.
 *
 * Список читается один раз на владельца, поэтому о файле, приложённом (или
 * отменённом) мимо панели, хук узнаёт только по `refreshSignal` — он входит в
 * ключ запроса наравне с владельцем.
 *
 * Ошибки отдаются ключом перевода, а не готовым текстом: строку собирает
 * компонент, и она переезжает на другой язык вместе с открытой модалкой.
 *
 * @param {object}        p
 * @param {string}        p.ownerType      OWNER_TYPE.CHAT | OWNER_TYPE.DOCUMENT
 * @param {string|number} p.ownerId        null — владельца нет, запроса не будет
 * @param {number}        [p.refreshSignal]
 * @param {Function}      [p.onCountChange] (count) => void — бейдж свёрнутой панели
 * @param {Function}      [p.onDeleted]     (id) => void — файла действительно больше нет
 */
export default function useAttachments({ ownerType, ownerId, refreshSignal = 0, onCountChange, onDeleted }) {
  const [attachments, setAttachments] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [summarizingId, setSummarizingId] = useState(null);
  const [errorKey, setErrorKey] = useState(null);

  // Зеркало списка. Все записи идут через commit, поэтому оно не расходится со
  // стейтом, зато переживает await: обработчик, продолжившийся после ответа
  // сервера, собирает новый список из актуального, а не из того, что был в
  // замыкании на момент клика.
  const listRef = useRef([]);

  // Единственная точка записи списка. Счётчик наверх сообщаем отсюда, а не из
  // апдейтера setState: апдейтер обязан быть чистым, а в StrictMode он вдобавок
  // выполняется дважды — родительский setState оттуда уходил бы дважды за клик.
  const commit = useCallback(
    (next) => {
      listRef.current = next;
      setAttachments(next);
      onCountChange?.(next.length);
    },
    [onCountChange],
  );

  // Запрос, который уже запущен, — чтобы список читался один раз на ключ: не
  // дважды под StrictMode и не заново на посторонний ре-рендер родителя.
  const startedRef = useRef(null);
  const requestKey = ownerId ? `${ownerType}:${ownerId}#${refreshSignal}` : null;
  // Ключ, ответ на который уже пришёл. Спиннер выводится отсюда: отдельным
  // состоянием он был бы setState прямо в теле эффекта, то есть лишним рендером
  // на каждое открытие панели.
  const [loadedKey, setLoadedKey] = useState(null);
  const loading = requestKey !== null && loadedKey !== requestKey;

  useEffect(() => {
    if (!requestKey || startedRef.current === requestKey) return;
    startedRef.current = requestKey;
    attachmentApi
      .list(ownerType, ownerId)
      .then((data) => (Array.isArray(data) ? data : []))
      .catch(() => [])
      .then((list) => {
        // Ответ мог обогнать более свежий запрос (два подряд refreshSignal):
        // тогда он не только показал бы устаревший список, но и отбросил бы
        // loadedKey назад — спиннер завис бы навсегда, потому что запрос по
        // текущему ключу уже запущен и повторно не пойдёт.
        if (startedRef.current !== requestKey) return;
        setLoadedKey(requestKey);
        commit(list);
      });
  }, [requestKey, ownerType, ownerId, commit]);

  const upload = useCallback(
    async (file) => {
      if (!file || !ownerId) return;
      setUploading(true);
      try {
        const added = await attachmentApi.upload(ownerType, ownerId, file);
        commit([...listRef.current, added]);
      } catch (err) {
        console.error('Upload error:', err);
        setErrorKey('attachments.errorUpload');
      } finally {
        setUploading(false);
      }
    },
    [ownerType, ownerId, commit],
  );

  const remove = useCallback(
    async (id) => {
      try {
        const res = await attachmentApi.delete(id);
        // requestRaw не бросает на !ok. Без этой проверки отказ сервера выглядел
        // бы как успешное удаление: строка ушла бы из таблицы, onDeleted снял бы
        // чип в композере, а файл остался бы на месте — до перезагрузки панели.
        // 404 — исключение: файла и правда больше нет (удалён в другой вкладке),
        // локальное удаление корректно.
        if (!res.ok && res.status !== 404) {
          setErrorKey('attachments.errorDelete');
          return;
        }
        commit(listRef.current.filter((a) => a.id !== id));
        onDeleted?.(id);
      } catch {
        setErrorKey('attachments.errorDelete');
      }
    },
    [commit, onDeleted],
  );

  const summarize = useCallback(
    async (id) => {
      setSummarizingId(id);
      try {
        const updated = await attachmentApi.summarize(id);
        commit(listRef.current.map((a) => (a.id === id ? updated : a)));
      } catch {
        setErrorKey('attachments.errorSummarize');
      } finally {
        setSummarizingId(null);
      }
    },
    [commit],
  );

  const dismissError = useCallback(() => setErrorKey(null), []);

  return { attachments, loading, uploading, summarizingId, errorKey, dismissError, upload, remove, summarize };
}
