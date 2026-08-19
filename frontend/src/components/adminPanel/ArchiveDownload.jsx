import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import OperationRow from '../common/ui/OperationRow';
import { IconArchive } from '../../icons';
import api from '../../api/documentsApi';

// ─── Операция: скачать всё дерево одним архивом ──────────────────────────────
// Экспорт выше кладёт файлы в серверную папку — увидеть их может только тот, у
// кого есть доступ к файловой системе сервера. Здесь та же раскладка приезжает
// в браузер: распаковал, поправил, положил обратно в папку экспорта и импортировал.
//
// Строка без состояний намеренно: скачивание ведёт браузер, а не приложение.
// Прогресс есть у него в собственной панели загрузок, и повторять его нечем —
// ответ уходит потоком, сервер не знает, сколько байт получится.

const ArchiveDownload = () => {
  const { t } = useTranslation('settings');
  const [meta, setMeta] = useState(false);

  return (
    <OperationRow
      icon={<IconArchive size={18} />}
      title={t('admin.bulk.archive.title')}
      desc={t('admin.bulk.archive.desc')}
      labels={{ run: t('admin.bulk.archive.run') }}
      state="idle"
      runVariant="ghost"
      runHref={api.archiveUrl(meta)}
    >
      <label className="admin-check">
        <input type="checkbox" checked={meta} onChange={(e) => setMeta(e.target.checked)} />
        {t('admin.bulk.export.metaCheckbox')}
      </label>
    </OperationRow>
  );
};

export default ArchiveDownload;
