import { useTranslation } from 'react-i18next';
import { DiffLines, PatchHeader, patchParts } from '@/components/chatPanel/messages/diffRender';

/**
 * Незакоммиченные изменения одного файла в центре панели.
 *
 * Раскраска, нумерация строк и вынесенная над блоком кода шапка патча — общие
 * с блоком изменений под ответом ИИ (diffRender): один и тот же патч одного и
 * того же файла не должен выглядеть в чате и в файловом браузере по-разному.
 * `<pre>` здесь свой — моноширинный фон и горизонтальный скролл у каждого
 * места вызова собственные.
 */
const ChangeDiffView = ({ diff }) => {
  const { t } = useTranslation('files');
  const { header, patch } = patchParts(diff.entry ?? {});

  if (diff.loading) return <div className="file-content__empty">{t('loading')}</div>;
  if (diff.error) return <div className="file-content__empty">{t('changes.loadError')}</div>;
  // Файл открыт из дерева, а diff-режим остался включённым: изменений нет —
  // это ответ, а не ошибка.
  if (!diff.entry) return <div className="file-content__empty">{t('changes.noChanges')}</div>;
  // Изменение есть, а показать его нечем: бинарный, пустой или слишком большой
  // файл — бэкенд для таких патч не собирает (см. GitService.untrackedDiffEntry).
  if (!diff.entry.patch) return <div className="file-content__empty">{t('changes.noPatch')}</div>;

  return (
    <>
      {/* Шапка патча — метаданные файла, поэтому она снаружи блока кода. */}
      <PatchHeader lines={header} />
      <pre className="file-diff">
        <DiffLines patch={patch} lineNumbers />
      </pre>
    </>
  );
};

export default ChangeDiffView;
