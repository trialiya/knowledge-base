// ─────────────────────────────────────────────────────────────────────────────
// Центральный реестр SVG-иконок приложения.
//
// Принципы:
//  • viewBox 0 0 24 24, stroke="currentColor", fill="none" — основной стандарт
//  • viewBox 0 0 16 16 — для компактных вариантов (суффикс Small)
//  • Специальные viewBox (16x16, 14x14) помечены в JSDoc
//  • Параметр size контролирует ширину/высоту; дефолты соответствуют прежним значениям
//
// Backward-compat: knowledgeBasePanel/icons.jsx и common/menuIcons.jsx
// реэкспортируют всё отсюда — существующие импорты не нужно менять.
// ─────────────────────────────────────────────────────────────────────────────

// Общий набор SVG-атрибутов для stroke-иконок (24×24)
const s = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
};

// ─── Navigation / tree ───────────────────────────────────────────────────────

export const IconFolder = ({ size = 15 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
  </svg>
);

export const IconDoc = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  </svg>
);

/** Правый шеврон с анимацией поворота (для раскрывающихся узлов дерева). */
export const IconChevron = ({ open }) => (
  <svg
    width="12"
    height="12"
    viewBox="0 0 24 24"
    {...s}
    strokeWidth="2.5"
    style={{ transform: open ? 'rotate(90deg)' : 'rotate(0deg)', transition: 'transform 0.18s', flexShrink: 0 }}
  >
    <polyline points="9 18 15 12 9 6" />
  </svg>
);

/** Правый шеврон без анимации (статичный). */
export const IconChevronRight = ({ size = 10 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <polyline points="9 18 15 12 9 6" />
  </svg>
);

/** Нижний шеврон (дропдауны, селекторы). Принимает className для CSS-вращения. */
export const IconChevronDown = ({ size = 12, className }) => (
  <svg
    className={className}
    width={size}
    height={size}
    viewBox="0 0 24 24"
    {...s}
    strokeWidth="2.4"
  >
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

export const IconArrowLeft = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <line x1="19" y1="12" x2="5" y2="12" />
    <polyline points="12 19 5 12 12 5" />
  </svg>
);

/** Стрелка вниз (кнопка «прокрутить к последнему сообщению»). */
export const IconArrowDown = ({ size = 18 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.4">
    <line x1="12" y1="5" x2="12" y2="19" />
    <polyline points="19 12 12 19 5 12" />
  </svg>
);

// ─── Actions / CRUD ──────────────────────────────────────────────────────────

export const IconPlus = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <line x1="12" y1="5" x2="12" y2="19" />
    <line x1="5" y1="12" x2="19" y2="12" />
  </svg>
);

export const IconEdit = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
  </svg>
);

export const IconTrash = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <polyline points="3 6 5 6 21 6" />
    <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
    <path d="M10 11v6" />
    <path d="M14 11v6" />
    <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2" />
  </svg>
);

/** X-крест — закрытие, сброс. */
export const IconX = ({ size = 12 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <line x1="18" y1="6" x2="6" y2="18" />
    <line x1="6" y1="6" x2="18" y2="18" />
  </svg>
);

/** Галочка — подтверждение выбора (24×24 viewBox, currentColor). */
export const IconCheck = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <polyline points="20 6 9 17 4 12" />
  </svg>
);

// ─── Content / file operations ────────────────────────────────────────────────

/** Клипборд — копировать (24×24 viewBox, стандартный). */
export const IconCopy = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
  </svg>
);

/**
 * Компактный клипборд (16×16 viewBox) — для кнопок внутри блоков кода
 * и мелких тулбаров, где 24×24 viewBox выглядит грубовато.
 */
export const IconCopySmall = ({ size = 13 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 16 16"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.6"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" />
    <path d="M10.5 5.5V3.5a1.5 1.5 0 0 0-1.5-1.5H3.5A1.5 1.5 0 0 0 2 3.5V9a1.5 1.5 0 0 0 1.5 1.5h2" />
  </svg>
);

/**
 * Зелёная галочка-подтверждение (16×16 viewBox) — состояние «скопировано».
 * Не принимает size — размер задаётся родителем через width/height атрибуты.
 */
export const IconCopied = ({ size = 12 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 16 16"
    fill="none"
    stroke="#34a853"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <path d="M3 8.5l3 3 7-7.5" />
  </svg>
);

export const IconRefresh = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 16 16" fill="none">
    <path
      d="M13.65 2.35A7.96 7.96 0 0 0 8 0C3.58 0 0 3.58 0 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 8 14 6 6 0 1 1 8 2c1.66 0 3.14.69 4.22 1.78L9 7h7V0l-2.35 2.35z"
      fill="currentColor"
    />
  </svg>
);

export const IconUpload = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="17 8 12 3 7 8" />
    <line x1="12" y1="3" x2="12" y2="15" />
  </svg>
);

export const IconPaperclip = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" />
  </svg>
);

export const IconEye = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

export const IconEyeOff = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
    <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
    <line x1="1" y1="1" x2="23" y2="23" />
  </svg>
);

export const IconLock = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
    <path d="M7 11V7a5 5 0 0 1 10 0v4" />
  </svg>
);

export const IconExpand = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <polyline points="15 3 21 3 21 9" />
    <polyline points="9 21 3 21 3 15" />
    <line x1="21" y1="3" x2="14" y2="10" />
    <line x1="3" y1="21" x2="10" y2="14" />
  </svg>
);

export const IconHistory = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M3 3v5h5" />
    <path d="M3.05 13A9 9 0 1 0 6 5.3L3 8" />
    <path d="M12 7v5l3 2" />
  </svg>
);

export const IconSummarize = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <line x1="4" y1="6" x2="20" y2="6" />
    <line x1="4" y1="10" x2="14" y2="10" />
    <line x1="4" y1="14" x2="18" y2="14" />
    <line x1="4" y1="18" x2="10" y2="18" />
  </svg>
);

// ─── Rich text / markdown editor ──────────────────────────────────────────────

export const IconBold = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <path d="M6 4h8a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z" />
    <path d="M6 12h9a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z" />
  </svg>
);

export const IconItalic = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <line x1="19" y1="4" x2="10" y2="4" />
    <line x1="14" y1="20" x2="5" y2="20" />
    <line x1="15" y1="4" x2="9" y2="20" />
  </svg>
);

export const IconCode = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <polyline points="16 18 22 12 16 6" />
    <polyline points="8 6 2 12 8 18" />
  </svg>
);

export const IconCodeBlock = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <polyline points="9 9 7 12 9 15" />
    <polyline points="15 9 17 12 15 15" />
  </svg>
);

export const IconLink = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
    <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
  </svg>
);

export const IconH1 = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <path d="M4 12h8" />
    <path d="M4 18V6" />
    <path d="M12 18V6" />
    <path d="M17 12l3-2v8" />
  </svg>
);

export const IconList = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <line x1="8" y1="6" x2="21" y2="6" />
    <line x1="8" y1="12" x2="21" y2="12" />
    <line x1="8" y1="18" x2="21" y2="18" />
    <line x1="3" y1="6" x2="3.01" y2="6" />
    <line x1="3" y1="12" x2="3.01" y2="12" />
    <line x1="3" y1="18" x2="3.01" y2="18" />
  </svg>
);

export const IconOrderedList = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <line x1="10" y1="6" x2="21" y2="6" />
    <line x1="10" y1="12" x2="21" y2="12" />
    <line x1="10" y1="18" x2="21" y2="18" />
    <path d="M4 6h1v4" />
    <path d="M4 10h2" />
    <path d="M6 18H4c0-1 2-1.4 2-2.6C6 14.5 5.4 14 4.6 14H4" />
  </svg>
);

export const IconChecklist = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <polyline points="3 7 5 9 9 5" />
    <polyline points="3 17 5 19 9 15" />
    <line x1="13" y1="7" x2="21" y2="7" />
    <line x1="13" y1="17" x2="21" y2="17" />
  </svg>
);

export const IconQuote = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M3 21c3 0 7-1 7-8V5c0-1.25-.756-2.017-2-2H4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2 1 0 1 0 1 1v1c0 1-1 2-2 2s-1 .008-1 1.031V20c0 1 0 1 1 1z" />
    <path d="M15 21c3 0 7-1 7-8V5c0-1.25-.757-2.017-2-2h-4c-1.25 0-2 .75-2 1.972V11c0 1.25.75 2 2 2h.75c0 2.25.25 4-2.75 4v3c0 1 0 1 1 1z" />
  </svg>
);

export const IconStrike = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <path d="M16 4H9a3 3 0 0 0-2.83 4" />
    <path d="M14 12a4 4 0 0 1 0 8H6" />
    <line x1="4" y1="12" x2="20" y2="12" />
  </svg>
);

export const IconHr = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.5">
    <line x1="3" y1="12" x2="21" y2="12" />
  </svg>
);

export const IconImage = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <circle cx="8.5" cy="8.5" r="1.5" />
    <polyline points="21 15 16 10 5 21" />
  </svg>
);

export const IconTable = ({ size = 13 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <line x1="3" y1="9" x2="21" y2="9" />
    <line x1="3" y1="15" x2="21" y2="15" />
    <line x1="9" y1="3" x2="9" y2="21" />
    <line x1="15" y1="3" x2="15" y2="21" />
  </svg>
);

// ─── AI / special ─────────────────────────────────────────────────────────────

/** Звёздочка-спарк (16×16 viewBox) — индикатор AI-действий, кнопка суммаризации. */
export const IconSparkle = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 16 16" fill="none">
    <path
      d="M8 1.5C8 1.5 8.6 4.2 10 5.5C11.4 6.8 14 7.5 14 7.5C14 7.5 11.4 8.2 10 9.5C8.6 10.8 8 13.5 8 13.5C8 13.5 7.4 10.8 6 9.5C4.6 8.2 2 7.5 2 7.5C2 7.5 4.6 6.8 6 5.5C7.4 4.2 8 1.5 8 1.5Z"
      stroke="currentColor"
      strokeWidth="1.4"
      strokeLinejoin="round"
      fill="currentColor"
      fillOpacity="0.15"
    />
    <path
      d="M13 1C13 1 13.3 2.2 14 2.8C14.7 3.4 16 3.5 16 3.5C16 3.5 14.7 3.7 14 4.3C13.3 4.9 13 6 13 6C13 6 12.7 4.9 12 4.3C11.3 3.7 10 3.5 10 3.5C10 3.5 11.3 3.4 12 2.8C12.7 2.2 13 1 13 1Z"
      stroke="currentColor"
      strokeWidth="1.1"
      strokeLinejoin="round"
      fill="currentColor"
      fillOpacity="0.2"
    />
  </svg>
);

/** Спиннер-спарк — состояние «AI генерирует». Требует CSS-анимации класса .spin. */
export const IconSparkleLoading = ({ size = 14 }) => (
  <svg width={size} height={size} viewBox="0 0 14 14" fill="none" className="spin">
    <circle cx="7" cy="7" r="5.5" stroke="currentColor" strokeWidth="1.5" strokeDasharray="22 10" />
  </svg>
);

// ─── Chat / input ─────────────────────────────────────────────────────────────

/** Самолётик — отправить сообщение. */
export const IconSend = ({ size = 18 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s} strokeWidth="2.2">
    <line x1="22" y1="2" x2="11" y2="13" />
    <polygon points="22 2 15 22 11 13 2 9 22 2" />
  </svg>
);

/** Стоп-квадрат — прервать стриминг. */
export const IconStop = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor">
    <rect x="4" y="4" width="16" height="16" rx="3" />
  </svg>
);

/** Звезда — избранное/фаворит. Принимает filled для заполненного варианта. */
export const IconStar = ({ size = 14, filled = false }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    fill={filled ? 'currentColor' : 'none'}
    stroke="currentColor"
    strokeWidth="1.8"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <polygon points="12 2 15 9 22 9.3 16.5 13.8 18.5 21 12 16.8 5.5 21 7.5 13.8 2 9.3 9 9" />
  </svg>
);

// ─── Admin / menu ─────────────────────────────────────────────────────────────

/** Вертикальное троеточие — триггер контекстного меню. */
export const IconDots = ({ size = 18 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" stroke="none">
    <circle cx="12" cy="5" r="1.7" />
    <circle cx="12" cy="12" r="1.7" />
    <circle cx="12" cy="19" r="1.7" />
  </svg>
);

/** Глобус — переключение языка. */
export const IconWorld = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <circle cx="12" cy="12" r="10" />
    <line x1="2" y1="12" x2="22" y2="12" />
    <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
  </svg>
);

/** Гаечный ключ — администрирование. */
export const IconTool = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
  </svg>
);

/** Шестерёнка — настройки. */
export const IconSettings = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);

/** Стрелка вниз в лоток — экспорт/скачивание. */
export const IconDownload = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
    <polyline points="7 10 12 15 17 10" />
    <line x1="12" y1="15" x2="12" y2="3" />
  </svg>
);

/** Цилиндр БД — семантический индекс / эмбеддинги. */
export const IconDatabase = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <ellipse cx="12" cy="5" rx="9" ry="3" />
    <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3" />
    <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5" />
  </svg>
);

/** Молния — быстрое действие (перегенерация). */
export const IconBolt = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
  </svg>
);

/** Ластик — очистка кэша. */
export const IconEraser = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M20 20H8.5L3 14.5a2 2 0 0 1 0-2.83l9-9a2 2 0 0 1 2.83 0l5 5a2 2 0 0 1 0 2.83L13 20" />
    <line x1="18" y1="13" x2="11" y2="6" />
  </svg>
);

/** Облачко сообщения — настройки чата / библиотека фраз. */
export const IconMessage = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" />
  </svg>
);

/** Ползунки — выбор/настройка моделей. */
export const IconSliders = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <line x1="4" y1="21" x2="4" y2="14" />
    <line x1="4" y1="10" x2="4" y2="3" />
    <line x1="12" y1="21" x2="12" y2="12" />
    <line x1="12" y1="8" x2="12" y2="3" />
    <line x1="20" y1="21" x2="20" y2="16" />
    <line x1="20" y1="12" x2="20" y2="3" />
    <line x1="1" y1="14" x2="7" y2="14" />
    <line x1="9" y1="8" x2="15" y2="8" />
    <line x1="17" y1="16" x2="23" y2="16" />
  </svg>
);

/** Документ с текстом — системный промпт. */
export const IconFileText = ({ size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" {...s}>
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
    <line x1="16" y1="13" x2="8" y2="13" />
    <line x1="16" y1="17" x2="8" y2="17" />
    <line x1="10" y1="9" x2="8" y2="9" />
  </svg>
);
