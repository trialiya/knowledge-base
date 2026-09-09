import { renderHook, act } from '@testing-library/react';
import useUnsavedViewGuard from './useUnsavedViewGuard';
import { setEditorDirty, clearEditorDirty } from '@/components/knowledgeBasePanel/editor/editorDirtyStore';

afterEach(() => clearEditorDirty());

const mount = (view) => {
  const switchView = vi.fn();
  const hook = renderHook(() => useUnsavedViewGuard({ view, switchView }));
  return { switchView, ...hook };
};

describe('уход из базы знаний с несохранёнными правками', () => {
  it('без правок переключает раздел сразу', () => {
    const { result, switchView } = mount('knowledge');
    act(() => result.current.goView('files'));
    expect(switchView).toHaveBeenCalledWith('files');
    expect(result.current.pendingView).toBeNull();
  });

  it('с правками спрашивает и переходит только после подтверждения', () => {
    setEditorDirty('inline', true);
    const { result, switchView } = mount('knowledge');
    act(() => result.current.goView('chat'));
    expect(switchView).not.toHaveBeenCalled();
    expect(result.current.pendingView).toBe('chat');

    act(() => result.current.confirmLeave());
    expect(switchView).toHaveBeenCalledWith('chat');
    expect(result.current.pendingView).toBeNull();
  });

  it('отказ оставляет на месте', () => {
    setEditorDirty('inline', true);
    const { result, switchView } = mount('knowledge');
    act(() => result.current.goView('chat'));
    act(() => result.current.cancelLeave());
    expect(switchView).not.toHaveBeenCalled();
    expect(result.current.pendingView).toBeNull();
  });

  it('из других разделов правки не держат', () => {
    setEditorDirty('inline', true);
    const { result, switchView } = mount('chat');
    act(() => result.current.goView('files'));
    expect(switchView).toHaveBeenCalledWith('files');
  });
});

describe('переход со своим действием', () => {
  // Ссылка на файл открывает «Файлы» сразу на пути — переход делает openFilePath,
  // а не switchView. С правками он обязан ждать ответа вместе с разделом: иначе
  // вопрос задан, а в файл ушли всё равно.
  it('с правками действие откладывается до подтверждения', () => {
    setEditorDirty('inline', true);
    const go = vi.fn();
    const { result, switchView } = mount('knowledge');
    act(() => result.current.goView('files', go));
    expect(go).not.toHaveBeenCalled();
    expect(result.current.pendingView).toBe('files');

    act(() => result.current.confirmLeave());
    expect(go).toHaveBeenCalledTimes(1);
    expect(switchView).not.toHaveBeenCalled();
  });

  it('без правок действие идёт сразу и вместо switchView', () => {
    const go = vi.fn();
    const { result, switchView } = mount('knowledge');
    act(() => result.current.goView('files', go));
    expect(go).toHaveBeenCalledTimes(1);
    expect(switchView).not.toHaveBeenCalled();
  });

  it('отказ не запускает отложенное действие', () => {
    setEditorDirty('inline', true);
    const go = vi.fn();
    const { result } = mount('knowledge');
    act(() => result.current.goView('files', go));
    act(() => result.current.cancelLeave());
    expect(go).not.toHaveBeenCalled();
  });
});
