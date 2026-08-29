import { useTranslation } from 'react-i18next';
import InfoList from '@/components/common/ui/InfoList';
import { formatDateTime } from '@/utils/formatting';
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
 * Токенов здесь нет: они читаются как один набор и стоят своей вкладкой «Usage» (`ChatUsage`).
 */
const ChatInfo = ({ chat, modelLabel, modeLabel, projectLabel }) => {
  const { t, i18n } = useTranslation('chat');

  if (!chat) {
    return <p className="info-list__hint">{t('window.selectChat')}</p>;
  }

  const isDraft = chat.id === DRAFT_CHAT_ID;
  // Пока чат не переименован вручную, отображаемое имя И ЕСТЬ тема от ИИ —
  // повторять её второй строкой незачем. Строка появляется только когда они
  // разошлись, то есть когда пользователь задал своё название.
  const aiTopic = chat.aiTopic && chat.aiTopic !== chat.title ? chat.aiTopic : null;

  const rows = [
    { label: t('info.title'), value: chat.title },
    { label: t('info.aiTopic'), value: aiTopic },
    { label: t('info.created'), value: formatDateTime(chat.createdAt, i18n.language) },
    { label: t('info.updated'), value: formatDateTime(chat.updatedAt, i18n.language) },
    { label: t('info.model'), value: modelLabel },
    { label: t('info.mode'), value: modeLabel },
    { label: t('info.project'), value: projectLabel },
    { label: t('info.id'), value: isDraft ? null : chat.id, mono: true },
  ];

  return <InfoList rows={rows} note={isDraft ? t('info.draftNote') : null} />;
};

export default ChatInfo;
