import { useEffect, useMemo, useState } from 'react';
import gitApi from '@/api/gitApi';

/**
 * Разделы markdown-файла — структура, которую бэкенд строит тем же разбором,
 * что оглавление документа базы знаний (MarkdownSections), поэтому пути
 * разделов здесь те же, что принимают инструменты документов.
 *
 * `rev` — снимок ревизии ('' — рабочее дерево). `refreshToken` — сигнал
 * «показанное могло устареть» от панели: правка, pull или откат меняют и
 * заголовки. Хук смонтирован только с раскрытой вкладкой — до неё запроса нет.
 *
 * @returns {{ sections: object[], loading: boolean, error: boolean }}
 */
export default function useFileSections({ path, project, rev = '', refreshToken = 0 }) {
  // Ответ вместе с ключом запроса, которому он принадлежит: чужой ключ — ответа
  // ещё нет, и состояние выводится при рендере, без сброса эффектом.
  const key = `${project}\n${rev}\n${path}\n${refreshToken}`;
  const [answer, setAnswer] = useState(null);

  useEffect(() => {
    const controller = new AbortController();
    gitApi
      .getFileOutline(path, { rev, project, signal: controller.signal })
      .then((outline) => {
        if (controller.signal.aborted) return;
        setAnswer({ key, sections: outline?.symbols ?? [], loading: false, error: false });
      })
      .catch((err) => {
        if (controller.signal.aborted || err.name === 'AbortError') return;
        setAnswer({ key, sections: [], loading: false, error: true });
      });
    return () => controller.abort();
  }, [key, path, project, rev]);

  // Мемо, а не литерал: результат уходит в зависимости у вызывающих.
  const pending = useMemo(() => ({ sections: [], loading: true, error: false }), []);
  return answer?.key === key ? answer : pending;
}
