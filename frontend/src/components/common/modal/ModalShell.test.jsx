import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ModalShell from './ModalShell';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

const pressCtrlF = () => fireEvent.keyDown(window, { key: 'f', code: 'KeyF', ctrlKey: true });
const pressEscape = () => fireEvent.keyDown(document, { key: 'Escape' });

const findBar = () => document.querySelector('.modal-find');
const counter = () => document.querySelector('.modal-find__count')?.textContent;

/** Модалка с текстом внутри и таким же текстом на странице под ней. */
function renderModal(onClose = vi.fn()) {
  render(
    <>
      <p>отчёт под оверлеем</p>
      <ModalShell onClose={onClose}>
        <p>отчёт по задаче</p>
        <p>черновик отчёта</p>
      </ModalShell>
    </>,
  );
  return onClose;
}

describe('ModalShell find bar', () => {
  it('opens on Ctrl+F and suppresses the browser page search', () => {
    renderModal();
    expect(findBar()).toBeNull();

    // fireEvent возвращает результат dispatchEvent: false — шорткат перехвачен,
    // то есть браузер не покажет свой поиск по всей странице.
    const notPrevented = pressCtrlF();

    expect(findBar()).not.toBeNull();
    expect(notPrevented).toBe(false);
  });

  it('counts matches inside the dialog only, ignoring the page behind it', async () => {
    renderModal();
    pressCtrlF();

    await userEvent.type(screen.getByPlaceholderText('modalFind.placeholder'), 'отчёт');

    expect(counter()).toBe('1/2'); // «под оверлеем» — третье вхождение — не в счёт
  });

  it('walks matches with the nav buttons and wraps around', async () => {
    renderModal();
    pressCtrlF();
    await userEvent.type(screen.getByPlaceholderText('modalFind.placeholder'), 'отчёт');

    await userEvent.click(screen.getByTitle('modalFind.next'));
    expect(counter()).toBe('2/2');

    await userEvent.click(screen.getByTitle('modalFind.next'));
    expect(counter()).toBe('1/2');

    await userEvent.click(screen.getByTitle('modalFind.prev'));
    expect(counter()).toBe('2/2');
  });

  it('reports no matches for a query the dialog does not contain', async () => {
    renderModal();
    pressCtrlF();

    await userEvent.type(screen.getByPlaceholderText('modalFind.placeholder'), 'смета');

    expect(counter()).toBe('0/0');
  });

  it('closes the find bar on the first Escape and the modal on the second', () => {
    const onClose = renderModal();
    pressCtrlF();

    pressEscape();
    expect(findBar()).toBeNull();
    expect(onClose).not.toHaveBeenCalled();

    pressEscape();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('leaves Escape closing the modal when no find bar is open', () => {
    const onClose = renderModal();

    pressEscape();

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
