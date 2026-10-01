import './buttons.css';
import './segmentSwitch.css';

/**
 * Переключатель между несколькими состояниями одной области: вид ответа
 * инструмента, режим сравнения версий. `options` — `{ value, label, title?,
 * disabled? }`, `label` — подпись группы для экранного диктора.
 *
 * Группа кнопок с `aria-pressed`, а не `tablist`/`tab`: настоящие вкладки
 * требуют `tabpanel` с `aria-controls` и стрелок вместо Tab, а здесь — состояния
 * одной области, которая остаётся на месте.
 */
const SegmentSwitch = ({ options, value: current, onChange, label }) => (
  <div className="segment-switch" role="group" aria-label={label}>
    {options.map(({ value, label: text, title, disabled }) => (
      <button
        key={value}
        type="button"
        aria-pressed={current === value}
        className="btn btn--ghost btn--xs"
        title={title || undefined}
        disabled={disabled}
        onClick={() => onChange(value)}
      >
        {text}
      </button>
    ))}
  </div>
);

export default SegmentSwitch;
