import React, { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import chatApi from '../../api/chatApi';
import PanelSearch from '../common/PanelSearch';
import { highlightSubstring } from '../common/highlightMatch';
import { IconMessage } from '../../icons';

const DEBOUNCE_MS = 250;
const RESULT_LIMIT = 15;

/**
 * Поиск по чатам над списком: и по названию, и по содержимому сообщений
 * одновременно, результаты объединены по чату. Виджет целиком общий
 * (common/PanelSearch) — здесь только запрос и описание строки результата.
 */
const ChatSearch = ({ onSelect }) => {
  const { t } = useTranslation('chat');

  const search = useCallback((q, signal) => chatApi.searchChats(q, RESULT_LIMIT, signal), []);

  const describeItem = useCallback(
    (res, query) => ({
      icon: <IconMessage size={13} />,
      title: highlightSubstring(res.topic || t('window.defaultTitle'), query),
      subtitle: res.snippet ? highlightSubstring(res.snippet, query) : null,
      multiline: true,
      badge: res.messageMatchCount,
    }),
    [t],
  );

  return (
    <PanelSearch
      label={t('sidebarSearch.open')}
      placeholder={t('sidebarSearch.placeholder')}
      hint={t('sidebarSearch.hint')}
      search={search}
      describeItem={describeItem}
      getKey={(res) => res.conversationId}
      onSelect={onSelect}
      debounceMs={DEBOUNCE_MS}
    />
  );
};

export default ChatSearch;
