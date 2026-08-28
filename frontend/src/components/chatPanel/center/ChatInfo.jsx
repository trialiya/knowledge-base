import { useTranslation } from 'react-i18next';
import InfoList from '@/components/common/ui/InfoList';
import { formatDateTime } from '@/utils/formatting';
import { cacheShare, formatTokens } from '../messages/tokenUsage';
import { DRAFT_CHAT_ID } from '@/constants/storage';

/**
 * Вкладка «Инфо» правой панели чата: метаданные активного чата.
 *
 * Название и предложенная ИИ тема — разные поля (`userTopic`/`aiTopic` на бэке),
 * и здесь они специально показаны по отдельности: в шапке и в списке чатов
 * видно только итоговое имя, а по нему не понять, придумал его пользователь или
 * ассистент. Пустые поля отбрасывает InfoList, поэтому у чата без ИИ-темы
 * лишней строки не будет.
 *
 * У черновика («новый чат», ещё не сохранён на бэке) нет ни дат, ни id — от
 * списка останутся только название и выбранные модель/режим.
 *
 * Токены живут здесь во второй раз и не дублируют плашки: в шапке и под ответом стоит по одному
 * короткому числу, а тут — то, что в них не влезает и мельком не нужно: total input, output и кэш
 * за весь чат. Именно total input объясняет разрыв между «занято 11k» и тем, что чат стоил на
 * самом деле, — и объяснять его надо один раз и подробно, а не цифрой в подписи.
 */
const ChatInfo = ({ chat, modelLabel, modeLabel, projectLabel, usage }) => {
  const { t, i18n } = useTranslation('chat');

  if (!chat) {
    return <p className="info-list__hint">{t('window.selectChat')}</p>;
  }

  const isDraft = chat.id === DRAFT_CHAT_ID;
  // Пока чат не переименован вручную, отображаемое имя И ЕСТЬ тема от ИИ —
  // повторять её второй строкой незачем. Строка появляется только когда они
  // разошлись, то есть когда пользователь задал своё название.
  const aiTopic = chat.aiTopic && chat.aiTopic !== chat.title ? chat.aiTopic : null;

  // Занятый контекст и итоги — разные вопросы к одним данным: первый берётся у последнего
  // прогона (складывать контекст нельзя), остальные суммируются по прогонам. См. tokenUsage.js.
  const current = usage?.current;
  const totals = usage?.totals;

  const rows = [
    { label: t('info.title'), value: chat.title },
    { label: t('info.aiTopic'), value: aiTopic },
    { label: t('info.created'), value: formatDateTime(chat.createdAt, i18n.language) },
    { label: t('info.updated'), value: formatDateTime(chat.updatedAt, i18n.language) },
    { label: t('info.model'), value: modelLabel },
    { label: t('info.mode'), value: modeLabel },
    { label: t('info.project'), value: projectLabel },
    { label: t('info.id'), value: isDraft ? null : chat.id, mono: true },
    { label: t('info.contextTokens'), value: current ? formatTokens(current.contextTokens) : null },
    { label: t('info.inputTokens'), value: totals ? formatTokens(totals.promptTokens) : null },
    { label: t('info.outputTokens'), value: totals ? formatTokens(totals.outputTokens) : null },
    {
      label: t('info.cacheReadTokens'),
      value:
        totals && totals.cacheReadTokens > 0
          ? t('info.cachedValue', {
              cached: formatTokens(totals.cacheReadTokens),
              percent: cacheShare(totals),
            })
          : null,
    },
    {
      label: t('info.cacheWriteTokens'),
      value: totals && totals.cacheWriteTokens > 0 ? formatTokens(totals.cacheWriteTokens) : null,
    },
    { label: t('info.modelCalls'), value: totals ? String(totals.modelCalls) : null },
  ];

  // Сноска у черновика и сноска о неполноте не встречаются: у черновика нет ни одного прогона.
  const note = isDraft ? t('info.draftNote') : totals && usage?.partial ? t('info.usagePartial') : null;

  return <InfoList rows={rows} note={note} />;
};

export default ChatInfo;
