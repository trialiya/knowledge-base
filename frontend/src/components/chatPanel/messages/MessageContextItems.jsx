import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ContextChips from '../composer/ContextChips';
import AttachmentModal from '@/components/common/attachments/AttachmentModal';
import { CONTEXT_KIND } from '@/constants/contextKind';

/**
 * Приложенное к вопросу — чипами под текстом сообщения, с открытием по клику.
 *
 * Модалке нужен объект вложения, а в сообщении хранится только ссылка ({@code ref})
 * и подпись на момент отправки. Этого хватает: содержимое модалка грузит по id
 * сама, а имя из подписи — то, что пользователь видел, когда прикладывал файл.
 */
const MessageContextItems = ({ items }) => {
  const { t } = useTranslation('chat');
  const [viewing, setViewing] = useState(null);

  if (!items || items.length === 0) return null;

  const open = (item) => {
    if (item.kind !== CONTEXT_KIND.ATTACHMENT) return;
    setViewing({ id: Number(item.ref), fileName: item.label || item.ref });
  };

  return (
    <>
      <ContextChips items={items} onOpen={open} ariaLabel={t('contextItems.inMessage')} />
      {viewing && <AttachmentModal attachment={viewing} mode="content" onClose={() => setViewing(null)} />}
    </>
  );
};

export default MessageContextItems;
