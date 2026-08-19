import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Phrases from './Phrases';
import { fetchPhrases, toggleFavorite } from '@/api/phrasesApi';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

vi.mock('@/api/phrasesApi', () => ({
  fetchPhrases: vi.fn(),
  toggleFavorite: vi.fn(),
}));

const phrase = (id, label, category, favorite = false) => ({
  id,
  label,
  text: `текст ${id}`,
  category,
  favorite,
});

describe('Phrases', () => {
  beforeEach(() => {
    toggleFavorite.mockResolvedValue(undefined);
  });

  // Регрессия: фильтр по исчезнувшей категории должен именно СБРАСЫВАТЬСЯ. Если
  // подменять его только при отрисовке, выбор «Избранное» остаётся в состоянии —
  // и первая же новая звёздочка молча возвращает фильтр, хотя пользователь
  // смотрел на полный список.
  it('снятие последнего избранного сбрасывает фильтр насовсем', async () => {
    fetchPhrases.mockResolvedValue([phrase(1, 'Первая', 'git', true), phrase(2, 'Вторая', 'git')]);
    render(<Phrases onSelect={vi.fn()} />);

    await screen.findByText('Первая');
    await userEvent.click(screen.getByRole('button', { name: 'phrases.categoryFavorites' }));
    expect(screen.queryByText('Вторая')).not.toBeInTheDocument();

    // Снимаем единственное избранное — категория исчезает, показывается всё.
    await userEvent.click(screen.getAllByRole('button', { name: 'phrases.favoriteToggle' })[0]);
    await waitFor(() => expect(screen.getByText('Вторая')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'phrases.categoryFavorites' })).not.toBeInTheDocument();

    // Отмечаем другую фразу: «Избранное» появляется снова, но фильтром не становится.
    await userEvent.click(screen.getAllByRole('button', { name: 'phrases.favoriteToggle' })[1]);
    await screen.findByRole('button', { name: 'phrases.categoryFavorites' });
    expect(screen.getByText('Первая')).toBeInTheDocument();
    expect(screen.getByText('Вторая')).toBeInTheDocument();
  });
});
