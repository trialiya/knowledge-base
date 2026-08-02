import React from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ChatList from './ChatList';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('ChatList', () => {
  it('показывает кнопку удаления в каждой строке, когда чатов несколько', () => {
    const chats = [
      { id: 'chat-1', title: 'Первый' },
      { id: 'chat-2', title: 'Второй' },
    ];
    render(<ChatList chats={chats} activeChatId="chat-1" onSelectChat={vi.fn()} onDeleteChat={vi.fn()} />);

    expect(screen.getAllByRole('button', { name: 'list.delete' })).toHaveLength(2);
  });

  it('не показывает кнопку удаления на единственном обычном чате', () => {
    const chats = [{ id: 'chat-1', title: 'Единственный' }];
    render(<ChatList chats={chats} activeChatId="chat-1" onSelectChat={vi.fn()} onDeleteChat={vi.fn()} />);

    expect(screen.queryByRole('button', { name: 'list.delete' })).not.toBeInTheDocument();
  });

  it('показывает кнопку удаления на единственном notFound-чате (заглушке битой ссылки)', () => {
    const chats = [{ id: 'chat-1', title: '...', notFound: true }];
    render(<ChatList chats={chats} activeChatId="chat-1" onSelectChat={vi.fn()} onDeleteChat={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'list.delete' })).toBeInTheDocument();
  });

  it('клик по кнопке удаления вызывает onDeleteChat и не выбирает строку', async () => {
    const chats = [{ id: 'chat-1', title: '...', notFound: true }];
    const onDeleteChat = vi.fn();
    const onSelectChat = vi.fn();
    render(<ChatList chats={chats} activeChatId="chat-1" onSelectChat={onSelectChat} onDeleteChat={onDeleteChat} />);

    await userEvent.click(screen.getByRole('button', { name: 'list.delete' }));
    expect(onDeleteChat).toHaveBeenCalledWith('chat-1');
    expect(onSelectChat).not.toHaveBeenCalled();
  });
});
