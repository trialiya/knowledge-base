import { expandTokensForSend } from './fileChips';
import { parseCommitLink } from '@/components/common/preview/docLinkParsing';

// Чип коммита уходит модели ссылкой той же формы, какой ей велено ссылаться на
// коммиты, — и в проекте, которому коммит принадлежит.

vi.mock('@/i18n/index', () => ({
  default: { t: (key, params) => `${key}|${params.link ?? params.project ?? ''}|${params.subject ?? ''}` },
}));

/** Ссылка из развёрнутого текста — как её потом прочтёт лента. */
const linkIn = (text) => parseCommitLink(text.match(/\]\(([^)]+)\)/)[1]);

describe('expandTokensForSend: чип коммита', () => {
  it('становится ссылкой на коммит в названном проекте', async () => {
    const text = await expandTokensForSend('см. ⟦commit@other:abc1234:Fix parser⟧', 'kb');

    expect(text).toContain('chat:fileChips.commitRef|[`abc1234`](/files?rev=abc1234&project=other)|Fix parser');
    expect(linkIn(text)).toEqual({ hash: 'abc1234', project: 'other' });
  });

  it('чип с полным хешем ссылается по полному, а подписан коротким', async () => {
    const hash = '0123456789abcdef0123456789abcdef01234567';
    const text = await expandTokensForSend(`⟦commit@kb:${hash}:Fix⟧`, 'kb');

    expect(text).toContain(`[\`0123456\`](/files?rev=${hash}&project=kb)`);
  });

  it('токен без проекта — старая форма — ссылается в проект чата', async () => {
    const text = await expandTokensForSend('⟦commit:abc1234:Fix⟧', 'kb');

    expect(linkIn(text)).toEqual({ hash: 'abc1234', project: 'kb' });
  });
});
