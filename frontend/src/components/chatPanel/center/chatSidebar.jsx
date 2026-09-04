import ChatInfo from './ChatInfo';
import ChatUsage from './ChatUsage';
import ChatRepoPanel from '../git/ChatRepoPanel';
import AttachmentPanel from '@/components/common/attachments/AttachmentPanel';
import { IconBranch, IconChart, IconInfo, IconPaperclip } from '@/icons/index';
import { CHAT_TAB } from '@/constants/chatTabs';
import { RIGHT_TAB } from '@/constants/rightTabs';
import { OWNER_TYPE } from '@/constants/ownerType';
import { DRAFT_CHAT_ID } from '@/constants/storage';

/**
 * Вкладки правой панели чата: метаданные, токены и вложения.
 *
 * Функция-сборщик, а не компонент — как buildDetailTabs у базы знаний:
 * WorkspaceLayout принимает вкладки массивом ({ key, label, icon, badge,
 * content }) и сам решает, показать их рельсом или раскрытой панелью.
 */
export function buildChatTabs({
  t,
  chatId,
  infoChat,
  modelLabel,
  modeLabel,
  projectLabel,
  usage,
  attachmentCount,
  onAttachmentCountChange,
  attachmentsRefreshSignal,
  onAttachmentDeleted,
}) {
  return [
    {
      // «Инфо» первой — так же, как в базе знаний и файлах.
      key: RIGHT_TAB.INFO,
      label: t('tabs.info'),
      icon: <IconInfo size={16} />,
      content: <ChatInfo chat={infoChat} modelLabel={modelLabel} modeLabel={modeLabel} projectLabel={projectLabel} />,
    },
    {
      // Токены — второй вкладкой, сразу за «Инфо»: вопрос к ней («во что обошёлся чат») задают
      // тем же движением, что и вопрос «что это за чат», а вложения открывают реже.
      key: CHAT_TAB.USAGE,
      label: t('usage.tab'),
      icon: <IconChart size={16} />,
      content: <ChatUsage usage={usage} />,
    },
    {
      key: RIGHT_TAB.ATTACHMENTS,
      label: t('window.attachments'),
      icon: <IconPaperclip size={16} />,
      badge: attachmentCount,
      // Черновика на бэке ещё нет, и загружать в него нельзя: «new» — выдумка фронта,
      // а загрузка вложения заводит чат с тем id, который ей дали. Чат рождается с
      // настоящим UUID — из композера (скрепка) или с первого сообщения.
      content:
        !chatId || chatId === DRAFT_CHAT_ID ? (
          <p className="chat-empty-note">{chatId ? t('window.attachmentsInDraft') : t('window.selectChat')}</p>
        ) : (
          <AttachmentPanel
            key={chatId}
            ownerType={OWNER_TYPE.CHAT}
            ownerId={chatId}
            onCountChange={onAttachmentCountChange}
            refreshSignal={attachmentsRefreshSignal}
            onDeleted={onAttachmentDeleted}
          />
        ),
    },
  ];
}

/**
 * Вкладка «Репозиторий» — отдельным сборщиком, а не строкой в списке выше.
 *
 * Её состояние перечитывается после каждой правки файла инструментом прогона,
 * то есть заметно чаще остальных вкладок. Собранная вместе с ними, она тащила
 * бы за собой пересоздание панели вложений на каждую такую правку.
 *
 * Пусто там, где проект команд не разрешил: без них вкладка отвечала бы на
 * вопрос «где мы» тем же, что и «Инфо», и стоила бы третьей кнопки в шапке
 * ради повтора.
 */
export function buildRepoTab({ t, git, onCommit, onPush }) {
  if (!git?.capabilities?.commands) return [];
  return [
    {
      key: RIGHT_TAB.REPO,
      label: t('repo.tab'),
      icon: <IconBranch size={16} />,
      // Точка — весь бюджет на постоянное присутствие git в интерфейсе:
      // закрытая панель молчит, а про незакрытый merge молчать нельзя. Строкой,
      // а не флагом: глазами это точка, скринридеру — причина.
      alert: git.status?.merging ? t('files:git.merging') : false,
      content: <ChatRepoPanel git={git} onOpenCommit={onCommit} onOpenPush={onPush} />,
    },
  ];
}
