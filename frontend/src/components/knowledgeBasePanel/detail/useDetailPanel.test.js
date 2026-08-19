import { renderHook, act } from '@testing-library/react';
import useDetailPanel from './useDetailPanel';

describe('useDetailPanel', () => {
  it('подхватывает описание, пришедшее после монтирования', () => {
    // Состояние детали живёт в KnowledgeBase и монтируется ДО того, как документ
    // загрузился, — черновик обязан догнать пришедшее описание, иначе редактор
    // остаётся пустым и сразу «грязным» (value '' !== savedValue).
    const { result, rerender } = renderHook(({ saved, id }) => useDetailPanel(saved, id), {
      initialProps: { saved: '', id: null },
    });
    expect(result.current.contentDraft).toBe('');

    rerender({ saved: '# Документ', id: 76 });
    expect(result.current.contentDraft).toBe('# Документ');
  });

  it('не затирает несохранённые правки внешним обновлением', () => {
    const { result, rerender } = renderHook(({ saved, id }) => useDetailPanel(saved, id), {
      initialProps: { saved: 'исходный', id: 1 },
    });
    act(() => result.current.setContentDraft('мои правки'));
    rerender({ saved: 'обновлён на сервере', id: 1 });
    expect(result.current.contentDraft).toBe('мои правки');
  });

  it('при смене документа начинает с описания нового, а не с чужого черновика', () => {
    const { result, rerender } = renderHook(({ saved, id }) => useDetailPanel(saved, id), {
      initialProps: { saved: 'первый', id: 1 },
    });
    act(() => result.current.setContentDraft('правки в первом'));
    act(() => result.current.setFullscreen(true));

    rerender({ saved: 'второй', id: 2 });
    expect(result.current.contentDraft).toBe('второй');
    // Развёрнутый редактор относился к прошлому документу — его тоже сбрасываем.
    expect(result.current.fullscreen).toBe(false);
  });
});
