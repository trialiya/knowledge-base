import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import ConfigGroup, { ConfigRow, ConfigStatusRow } from '@/components/common/config/ConfigGroup';
import useConfigSnapshot from '@/components/common/config/useConfigSnapshot';
import settingsApi from '@/api/settingsApi';

/**
 * Снимок kb.search.* — лимиты и пороги трёх режимов поиска по базе знаний.
 *
 * Показывать это стоит именно из-за семантики: на профиле h2 она выключена
 * (application-h2.yaml), и до появления этой группы узнать об этом из интерфейса
 * было нельзя — поиск просто молча работал как keyword.
 */
const SearchSettings = () => {
  const { t } = useTranslation('settings');
  const { data: config, error } = useConfigSnapshot(settingsApi.getAiConfig);

  return (
    <ConfigGroup title={t('search.title')} subtitle={t('search.subtitle')} data={config} error={error}>
      <SearchSections config={config} />
    </ConfigGroup>
  );
};

const SearchSections = ({ config }) => {
  const { t } = useTranslation('settings');
  const { keyword, semantic, hybrid } = config.search;

  return (
    <>
      <SettingsSection label={t('search.keyword.label')}>
        <ConfigRow label={t('search.limit')} value={t('config.resultsValue', { count: keyword.limit })} />
      </SettingsSection>

      <SettingsSection label={t('search.semantic.label')}>
        <ConfigStatusRow label={t('search.semantic.status')} on={semantic.enabled} />
        <ConfigRow label={t('search.threshold')} value={semantic.threshold} />
        <ConfigRow label={t('search.limit')} value={t('config.resultsValue', { count: semantic.limit })} />
      </SettingsSection>

      <SettingsSection label={t('search.hybrid.label')}>
        <ConfigRow label={t('search.hybrid.keywordWeight')} value={hybrid.keywordWeight} />
        <ConfigRow label={t('search.hybrid.semanticWeight')} value={hybrid.semanticWeight} />
        <ConfigRow label={t('search.threshold')} value={hybrid.threshold} />
        <ConfigRow label={t('search.limit')} value={t('config.resultsValue', { count: hybrid.limit })} />
      </SettingsSection>
    </>
  );
};

export default SearchSettings;
