import { useTranslation } from 'react-i18next';
import { IconChevronRight } from '@/icons/index';

/**
 * Правая панель рабочей области — раскрытое состояние.
 *
 * Показывает ровно одну вкладку: её заголовок с кнопкой сворачивания и её тело.
 * Переключение вкладок живёт не здесь, а на рельсе иконок в WorkspaceLayout —
 * он виден всегда и служит tablist'ом для этого тела.
 *
 * props:
 *   tab     — { key, label, icon, badge, alert, content } (content — узел)
 *             badge — число (сколько), alert — строка-причина или true:
 *             точка «требует внимания» там, где считать нечего. Строку
 *             озвучивает скринридер — без неё вкладка молчала бы ровно о
 *             том состоянии, ради которого точка и заведена
 *   onClose — () => void
 */
const RightPanel = ({ tab, onClose }) => {
  const { t } = useTranslation();

  return (
    <aside className="workspace__side workspace__side--right" aria-label={tab.label}>
      <div className="workspace__side-head">
        <span className="workspace__side-title">
          {tab.icon}
          {tab.label}
          {tab.badge > 0 && <span className="workspace__tab-badge">{tab.badge}</span>}
          {/* Точка «здесь что-то не закрыто». Сама по себе она видна только
              глазами, поэтому рядом с ней — та же мысль словами: строка `alert`
              уезжает в доступное имя, а на экране остаётся невидимой. */}
          {tab.alert && (
            <span className="workspace__tab-dot">
              {typeof tab.alert === 'string' && <span className="workspace__a11y-only">{tab.alert}</span>}
            </span>
          )}
        </span>
        <button
          type="button"
          className="icon-btn"
          onClick={onClose}
          title={t('panels.collapseRight')}
          aria-label={t('panels.collapseRight')}
        >
          <IconChevronRight size={14} />
        </button>
      </div>
      <div className="workspace__side-body" id="ws-tabpanel" role="tabpanel" aria-labelledby={`ws-tab-${tab.key}`}>
        {tab.content}
      </div>
    </aside>
  );
};

export default RightPanel;
