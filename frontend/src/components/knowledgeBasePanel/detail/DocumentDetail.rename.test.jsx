import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import DocumentDetail from './DocumentDetail';

/**
 * Переименование обязано уйти РОВНО одним запросом: `onRename` и `onUpdate`
 * ведут в один и тот же `handleUpdate` (KnowledgeBase.jsx), поэтому вызов обоих
 * даёт два параллельных PUT — лишний снапшот в истории документа и гонку
 * optimistic locking, из-за которой пользователь видит ошибку сохранения на
 * успешном переименовании.
 */
// i18n в тестах не инициализируем — берём ключ как подпись.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const renderDetail = ({ onRename, onUpdate, contentDraft = '' }) =>
  render(
    <DocumentDetail
      node={{ id: 7, title: 'Старое имя', type: 'document', description: '' }}
      path={[]}
      onRename={onRename}
      onUpdate={onUpdate}
      onDelete={vi.fn()}
      onNavigate={vi.fn()}
      contentDraft={contentDraft}
      setContentDraft={vi.fn()}
    />,
  );

describe('DocumentDetail: два пути сохранения не пересекаются', () => {
  it('переименование уходит одним onRename и не дублируется через onUpdate', async () => {
    const user = userEvent.setup();
    const onRename = vi.fn();
    const onUpdate = vi.fn();
    const { container } = renderDetail({ onRename, onUpdate });

    await user.click(screen.getByText('Старое имя'));
    await user.clear(container.querySelector('.detail-header__edit'));
    await user.type(container.querySelector('.detail-header__edit'), 'Новое имя{Enter}');

    expect(onRename).toHaveBeenCalledTimes(1);
    expect(onRename).toHaveBeenCalledWith(7, 'Новое имя');
    expect(onUpdate).not.toHaveBeenCalled();
  });

  it('сохранение текста уходит одним onUpdate с описанием и не трогает имя', async () => {
    const user = userEvent.setup();
    const onRename = vi.fn();
    const onUpdate = vi.fn().mockResolvedValue(undefined);
    renderDetail({ onRename, onUpdate, contentDraft: 'новый текст' });

    await user.click(screen.getByText('editor.save'));

    expect(onUpdate).toHaveBeenCalledTimes(1);
    expect(onUpdate).toHaveBeenCalledWith(7, { description: 'новый текст' });
    expect(onRename).not.toHaveBeenCalled();
  });
});
