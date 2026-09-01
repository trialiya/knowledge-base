export const TOOL_STATUS = {
  STARTED: 'STARTED',
  OK: 'OK',
  ERROR: 'ERROR',
  // Исход вызова не сохранён: мету прогона записать не успели. Не «выполняется» и не «успех» —
  // бэкенд отвечает им там, где раньше отвечал OK (см. ToolCallService#invocationsFor).
  UNKNOWN: 'UNKNOWN',
};
