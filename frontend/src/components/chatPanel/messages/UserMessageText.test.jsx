import { render } from '@testing-library/react';
import UserMessageText from './UserMessageText';
import { commitUrl } from '@/navigation/urlScheme';

vi.mock('@/components/common/config/useProjectConfig', () => ({
  default: () => ({ defaultProjectId: 'kb' }),
}));

const command = (container) => container.querySelector('.user-message-text__command');

describe('UserMessageText', () => {
  it('выделяет команду и оставляет хвост обычным текстом', () => {
    const { container } = render(<UserMessageText text="/compact про миграции" />);

    expect(command(container).textContent).toBe('/compact');
    expect(container.querySelector('.user-message-text').textContent).toBe('/compact про миграции');
  });

  it('обычный вопрос ничем не выделен', () => {
    const { container } = render(<UserMessageText text="что делает /compact?" />);

    expect(command(container)).toBeNull();
    expect(container.querySelector('.user-message-text').textContent).toBe('что делает /compact?');
  });

  it('ссылка на коммит от чипа — кликабельный хеш, а не сырая разметка', () => {
    const { container, getByRole } = render(
      <UserMessageText text="почему в коммит [`abc1234`](/files?rev=abc1234&project=kb) — Fix вошло это?" />,
    );

    const link = getByRole('link', { name: 'abc1234' });
    expect(link).toHaveAttribute('href', commitUrl('abc1234', 'kb'));
    expect(container.querySelector('.user-message-text').textContent).toBe('почему в коммит abc1234 — Fix вошло это?');
  });

  it('прочая разметка, набранная руками, остаётся текстом', () => {
    const text = 'см. [доку](https://example.com) и [файл](/files?path=a.md)';
    const { container, queryByRole } = render(<UserMessageText text={text} />);

    expect(queryByRole('link')).toBeNull();
    expect(container.querySelector('.user-message-text').textContent).toBe(text);
  });
});
