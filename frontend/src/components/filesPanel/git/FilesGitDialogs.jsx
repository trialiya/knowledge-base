import { useTranslation } from 'react-i18next';
import CommitDialog from '@/components/common/git/CommitDialog';
import PushDialog from '@/components/common/git/PushDialog';
import ConfirmModal from '@/components/common/modal/ConfirmModal';
import ErrorModal from '@/components/common/modal/ErrorModal';
import GitPromptModal from './GitPromptModal';

/**
 * Окна git-команд файлового браузера: имя новой ветки, коммит, push, откат
 * правки и отказ команды. Какое из них открыто, решает useGitActions;
 * `dialogGit` — контракт окон коммита и push, дополненный панелью списком
 * незакоммиченного (см. FilesPanel).
 */
const FilesGitDialogs = ({ git, actions, dialogGit, notice, onDismissNotice }) => {
  const { t } = useTranslation('files');

  return (
    <>
      <GitPromptModal
        open={actions.naming}
        title={t('git.newBranch')}
        label={t('git.branchName')}
        hint={git.status ? t('git.branchFrom', { branch: git.status.current }) : undefined}
        placeholder="feature/…"
        confirmLabel={t('git.create')}
        onConfirm={actions.confirmNewBranch}
        onCancel={actions.cancelNewBranch}
      />
      {/* Те же окна, что открывает вкладка «Репозиторий» в чате: коммит там и
          здесь означает одно и то же (см. common/git). */}
      {actions.dialog === 'commit' && <CommitDialog git={dialogGit} onClose={actions.closeDialog} />}
      {actions.dialog === 'push' && <PushDialog git={dialogGit} onClose={actions.closeDialog} />}
      <ConfirmModal
        open={!!actions.discarding}
        title={t('git.discardTitle')}
        message={t('git.discardMessage', { path: actions.discarding })}
        confirmLabel={t('git.discardConfirm')}
        onConfirm={actions.confirmDiscard}
        onCancel={actions.cancelDiscard}
      />
      <ErrorModal
        open={!!notice}
        title={notice ? t(notice.titleKey) : ''}
        message={notice ? t(notice.messageKey, notice.params) : ''}
        onClose={onDismissNotice}
      />
    </>
  );
};

export default FilesGitDialogs;
