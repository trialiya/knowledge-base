import { renderHook, waitFor } from '@testing-library/react';
import useFolderChildren from './useFolderChildren';

vi.mock('@/api/documentsApi', () => ({
  default: { fetchChildren: vi.fn() },
}));

const folder = (children, total) => ({
  id: 7,
  type: 'folder',
  _childrenLoaded: true,
  _totalChildren: total,
  children,
});

const kid = (id) => ({ id, type: 'document', title: `Док ${id}` });

describe('useFolderChildren', () => {
  it('полный список из дерева не вызывает запроса и не показывает загрузку', () => {
    const loadChildren = vi.fn();
    const { result } = renderHook(() => useFolderChildren(folder([kid(1), kid(2)], 2), loadChildren));

    expect(result.current.loading).toBe(false);
    expect(result.current.children).toHaveLength(2);
    expect(loadChildren).not.toHaveBeenCalled();
  });

  // Регрессия: `loading` выведен из живого «дерево неполно», а запрос уходил
  // только на смену узла. Выбранная папка может стать неполной без смены узла —
  // refreshScope после мутации заменяет детей нулевой страницей, — и тогда
  // спиннер включался навсегда, без запроса, который мог бы его снять.
  it('папка, ставшая неполной без смены узла, дочитывается', async () => {
    const loadChildren = vi.fn().mockResolvedValue({ items: [], totalElements: 30 });

    // Сначала дерево держит полный список — запроса нет.
    const { result, rerender } = renderHook(({ node }) => useFolderChildren(node, loadChildren), {
      initialProps: { node: folder([kid(1), kid(2)], 2) },
    });
    expect(loadChildren).not.toHaveBeenCalled();

    // refreshScope заменил детей нулевой страницей: загружено 2 из 30.
    rerender({ node: folder([kid(1), kid(2)], 30) });

    await waitFor(() => expect(loadChildren).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(result.current.loading).toBe(false));
  });
});
