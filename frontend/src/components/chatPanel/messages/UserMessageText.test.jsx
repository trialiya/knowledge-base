import { render } from '@testing-library/react';
import UserMessageText from './UserMessageText';

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
});
