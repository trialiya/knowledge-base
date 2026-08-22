import { useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { UPLOAD_ACCEPT } from '@/constants/uploadAccept';
import ChatHeader from './ChatHeader';
import ChatSearchBar from './ChatSearchBar';
import MessageList from '../messages/MessageList';
import MessageInput from '../composer/MessageInput';

/**
 * Центральная колонка чата: шапка, find-бар, лента сообщений и композер.
 *
 * Состояния здесь нет — всё приходит пропами из ChatWindow, который и владеет
 * чатами. Исключение — ref скрытого файлового input: он существует только чтобы
 * скрепка в композере открыла системный диалог, и наверх ему не за чем.
 *
 * @param {object} p
 * @param {object} p.search  результат useInChatSearch плюс `inputRef` и `canSearch`
 * @param {object} p.model   { config, options, selected, onChange } для селектора модели
 * @param {object} p.mode    { options, selected, onChange } для селектора режима
 * @param {object} p.project { options, defaultId, selected, inLinks, missing, onChange } для селектора проекта
 */
const ChatCenter = ({
  chat,
  chatId,
  messages,
  loadingMessages,
  isStreaming,
  isChatEmpty,
  isActive,
  search,
  staged,
  initialText,
  composerDraftSignal,
  model,
  mode,
  project,
  onRename,
  onDelete,
  onNavigateToDoc,
  onLoadOlder,
  onRetry,
  onSend,
  onStop,
  onAttachFile,
  onUnstage,
  onTextChange,
}) => {
  const { t } = useTranslation('chat');
  const fileInputRef = useRef(null);

  // Чат недоступен: битая ссылка (notFound) либо отказ сервера при загрузке.
  // В обоих случаях ленты нет, а вместо композера — пояснение, почему нельзя писать.
  const unavailable = !!(chat?.notFound || chat?.loadError);

  return (
    <>
      {chat && (
        <ChatHeader
          chat={chat}
          canSearch={search.canSearch}
          searchOpen={search.open}
          onToggleSearch={() => (search.open ? search.close() : search.openBar())}
          onRename={onRename}
          onDelete={onDelete}
        />
      )}

      {search.open && search.canSearch && (
        <ChatSearchBar
          inputRef={search.inputRef}
          query={search.query}
          onQueryChange={search.setQuery}
          total={search.total}
          activeIndex={search.activeIndex}
          loading={search.loading}
          onPrev={search.goPrev}
          onNext={search.goNext}
          onClose={search.close}
        />
      )}

      {loadingMessages ? (
        <div className="loading-messages">{t('window.loadingMessages')}</div>
      ) : unavailable ? (
        <div className="chat-unavailable">
          <span className="chat-unavailable__icon">{chat?.notFound ? '🔍' : '⚠️'}</span>
          <span>{chat?.notFound ? t('window.notFoundTitle') : t('window.loadErrorTitle')}</span>
          <span className="chat-unavailable__desc">
            {chat?.notFound ? t('window.notFoundDesc') : t('window.loadErrorDesc', { status: chat?.loadError })}
          </span>
        </div>
      ) : (
        <MessageList
          key={chatId}
          conversationId={chatId}
          project={project?.inLinks}
          projectOptions={project?.options}
          // Подписи моделей под ответами — тот же список, что и в селекторе.
          modelOptions={model?.options}
          messages={messages}
          onNavigateToDoc={onNavigateToDoc}
          onLoadMore={onLoadOlder}
          onRetry={onRetry}
          hasMore={!!chat?.hasMore}
          canLoadMore={!isStreaming}
          activeSearchMid={search.activeMatchMid}
          searchQuery={search.open ? search.query.trim() : ''}
        />
      )}

      <input
        ref={fileInputRef}
        type="file"
        hidden
        accept={UPLOAD_ACCEPT}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) onAttachFile(file);
          e.target.value = '';
        }}
      />
      {unavailable ? (
        <div className="message-input-wrapper message-input-wrapper--disabled">
          <span className="message-input-disabled-note">
            {chat?.notFound ? t('window.notFoundInputNote') : t('window.loadErrorInputNote')}
          </span>
        </div>
      ) : (
        <MessageInput
          onSend={onSend}
          onStop={onStop}
          disabled={isStreaming}
          onAttach={() => fileInputRef.current?.click()}
          staged={staged}
          onUnstage={onUnstage}
          isEmpty={isChatEmpty && !loadingMessages}
          draftSignal={composerDraftSignal}
          active={isActive}
          chatId={chatId}
          initialText={initialText}
          onTextChange={onTextChange}
          model={model}
          mode={mode}
          project={project}
        />
      )}
    </>
  );
};

export default ChatCenter;
