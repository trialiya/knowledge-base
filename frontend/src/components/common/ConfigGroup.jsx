import React from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsContentHead } from './SettingsShell';
import { splitDuration } from '../../utils/formatting';
import './configGroup.css';

/**
 * Группа «Настроек»/«Администрирования», показывающая read-only снимок конфигурации:
 * заголовок, ветки загрузки и ошибки, тело. Раньше этот каркас был вписан в
 * единственную группу «Модели»; групп стало пять, и повторять его в каждой смысла нет.
 *
 * Пространство имён переводов — settings: компонент общий для двух страниц, но
 * обе живут в нём, а тексты «Загрузка…»/«Ошибка загрузки» у них одни и те же.
 *
 * props:
 *   title/subtitle — шапка группы
 *   data           — снимок из useConfigSnapshot (null, пока не загружен)
 *   error          — ошибка из useConfigSnapshot
 *   children       — секции группы, рендерятся только когда data пришла
 */
const ConfigGroup = ({ title, subtitle, data, error, children }) => {
  const { t } = useTranslation('settings');

  return (
    <>
      <SettingsContentHead title={title} subtitle={subtitle} />
      <div className="settings-content__body">
        {error && <p className="config-group__error">{error.message || t('config.errorLoading')}</p>}
        {!error && !data && <p className="config-group__loading">{t('config.loading')}</p>}
        {!error && data && children}
      </div>
    </>
  );
};

/**
 * Форматтер длительности: секунды → «10 мин». Хук, а не функция в utils, потому
 * что суффикс единицы — переводимый текст: utils/formatting выбирает единицу,
 * локаль даёт ей название. Таймауты, интервалы и аптайм показывают три группы,
 * и без него каждая заводила бы свою копию.
 */
export const useDurationFormat = () => {
  const { t } = useTranslation('settings');
  return (seconds) => {
    const { value, unit } = splitDuration(seconds);
    return t(`config.duration.${unit}`, { value });
  };
};

/**
 * Строка «лейбл → значение» снимка. Значение моноширинное: это всегда конфиг.
 *
 * Годится и для длинных значений — URL, путей, cron-выражений: строка целиком
 * переносится (см. `.set-row`), значение встаёт на свою строку и остаётся
 * выключенным вправо. Уводить его под метку по левому краю не нужно.
 *
 * `empty` — подпись вместо незаданного значения («не задан»).
 */
export const ConfigRow = ({ label, value, empty }) => (
  <div className="set-row">
    <span className="set-row__label">{label}</span>
    <span className="set-row__value">{value == null || value === '' ? empty : value}</span>
  </div>
);

/** Строка «лейбл → включён/отключён» — для состояния функции («Статус», «Кэш векторов»). */
export const ConfigStatusRow = ({ label, on }) => {
  const { t } = useTranslation('settings');
  return (
    <div className="set-row">
      <span className="set-row__label">{label}</span>
      <span className={`status-badge status-badge--${on ? 'on' : 'off'}`}>{on ? t('config.on') : t('config.off')}</span>
    </div>
  );
};

/**
 * Строка «лейбл → да/нет» — для утверждений, а не состояний: «Рабочее дерево
 * доступно на запись — включён» звучит как ошибка согласования, «да» не звучит.
 */
export const ConfigBoolRow = ({ label, value }) => {
  const { t } = useTranslation('settings');
  return (
    <div className="set-row">
      <span className="set-row__label">{label}</span>
      <span className={`status-badge status-badge--${value ? 'on' : 'off'}`}>
        {value ? t('config.yes') : t('config.no')}
      </span>
    </div>
  );
};

/**
 * Метка, под ней — содержимое блоком: для `<ConfigTags>`, набор которых в
 * строку справа не встаёт. Длинные строковые значения (URL, путь, cron) сюда
 * больше не идут — они остаются обычным `<ConfigRow>`, выключенным вправо.
 */
export const ConfigBlock = ({ label, children }) => (
  <div className="config-block">
    <span className="config-block__label">{label}</span>
    {children}
  </div>
);

/**
 * Список коротких значений (имена инструментов, подключения MCP) — переносится по
 * словам, потому что список инструментов саб-агента длиннее любой строки панели.
 */
export const ConfigTags = ({ items, empty }) => {
  if (!items || items.length === 0) {
    return <span className="config-tags__empty">{empty}</span>;
  }
  return (
    <div className="config-tags">
      {items.map((item) => (
        <span key={item} className="config-tags__item">
          {item}
        </span>
      ))}
    </div>
  );
};

export default ConfigGroup;
