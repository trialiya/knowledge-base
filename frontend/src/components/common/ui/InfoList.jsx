import { useTranslation } from 'react-i18next';
import { IconCopySmall, IconCopied } from '@/icons/index';
import useCopyFeedback from './useCopyFeedback';
import './infoList.css';

/**
 * Вкладка «Инфо» правой панели — общая для чата, базы знаний и файлов.
 *
 * Один и тот же вид метаданных во всех разделах: `label → value` списком,
 * плюс необязательная сноска под ним. Раньше это была вёрстка внутри
 * knowledgeBasePanel/DetailInfo (классы `detail-info__*`); теперь разметка и
 * стили живут здесь, а разделы поставляют только строки.
 *
 * Пустые значения (null / '' / undefined) отбрасываются: набор полей у разных
 * источников разный (дерево vs полный GET, файл vs каталог), и строк-прочерков
 * «—» в панели быть не должно.
 *
 * props:
 *   rows — [{ label, value, mono, block }]
 *          mono  — моноширинное значение (хеш, путь, id)
 *          block — значение под меткой и по левому краю: только для связного
 *                  текста в несколько строк (сообщение коммита), где выключка
 *                  вправо нечитаема. Длинному значению-строке (id, дата, путь)
 *                  block не нужен: строка переносится целиком, и значение
 *                  остаётся справа
 *   note — узел под списком (предупреждение/пояснение), необязателен
 *
 * У строки с текстовым значением есть кнопка копирования: большинство значений
 * здесь (id, хеш, путь) для того и нужны, чтобы вставить их куда-то ещё, а
 * выделить их мышью трудно — это не текст, а вёрстка списка. У значения-узла
 * кнопки нет: копировать в буфер нечего, в него ушло бы «[object Object]».
 */
const InfoList = ({ rows, note }) => {
  const { t } = useTranslation();
  const [copiedLabel, copy] = useCopyFeedback();
  const visible = rows.filter((row) => row && row.value != null && row.value !== '');

  return (
    <div className="info-list">
      <dl className="info-list__list">
        {visible.map((row) => {
          const copied = copiedLabel === row.label;
          const copyable = typeof row.value === 'string' || typeof row.value === 'number';
          return (
            <div className={`info-list__row${row.block ? ' info-list__row--block' : ''}`} key={row.label}>
              <dt className="info-list__label">{row.label}</dt>
              <dd className={`info-list__value${row.mono ? ' info-list__value--mono' : ''}`}>
                <span className="info-list__value-text">{row.value}</span>
                {copyable && (
                  <button
                    type="button"
                    className={`icon-btn info-list__copy-btn${copied ? ' icon-btn--done' : ''}`}
                    onClick={() => copy(String(row.value), row.label)}
                    title={copied ? t('copied') : t('copy')}
                    aria-label={`${copied ? t('copied') : t('copy')}: ${row.label}`}
                  >
                    {copied ? <IconCopied size={12} /> : <IconCopySmall size={12} />}
                  </button>
                )}
              </dd>
            </div>
          );
        })}
      </dl>
      {note && <p className="info-list__note">{note}</p>}
    </div>
  );
};

export default InfoList;
