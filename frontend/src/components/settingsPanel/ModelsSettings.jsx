import React from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsSection } from '../common/SettingsShell';
import ConfigGroup, { ConfigRow, ConfigStatusRow, ConfigTags, useDurationFormat } from '../common/ConfigGroup';
import useConfigSnapshot from '../common/useConfigSnapshot';
import settingsApi from '../../api/settingsApi';

/**
 * Снимок конфигурации моделей: параметры вызова, основная модель, агент поиска,
 * сжатие контекста и эмбеддинги. Всё read-only — источник истины application.yaml.
 */
const ModelsSettings = () => {
  const { t } = useTranslation('settings');
  const { data: config, error } = useConfigSnapshot(settingsApi.getAiConfig);

  return (
    <ConfigGroup title={t('models.title')} subtitle={t('models.subtitle')} data={config} error={error}>
      {/* Тело — отдельный компонент: ConfigGroup рендерит children только когда
          снимок пришёл, поэтому здесь можно разбирать config без проверок. */}
      <ModelsSections config={config} />
    </ConfigGroup>
  );
};

const ModelsSections = ({ config }) => {
  const { t } = useTranslation('settings');
  const duration = useDurationFormat();
  const { chat, searchCodebase, summarize, embedding } = config;
  const defaultId = chat.defaultModel?.id;
  const subagentSameModel = searchCodebase.modelId === defaultId;

  return (
    <>
      {/* ── Общее ── */}
      <SettingsSection label={t('models.general.label')}>
        <ConfigRow label={t('models.general.maxTokens')} value={chat.options?.maxTokens?.toLocaleString()} />
        <ConfigRow label={t('models.general.temperature')} value={chat.options?.temperature} />
        <ConfigRow label={t('models.general.topP')} value={chat.options?.topP} />
        {/* Строки «Окно памяти» здесь намеренно нет: MessageWindowChatMemory.maxMessages
            подрезает историю только на записи, а saveAll в ChatMemoryService — append-only,
            так что число ничего не ограничивает. Реальный предел контекста — пороги
            суммаризации ниже, в секции «Сжатие контекста». Подробности в ChatConfig#chatMemory. */}
        <ConfigRow label={t('models.general.requestTimeout')} value={duration(chat.options?.requestTimeoutSeconds)} />
        <ConfigRow label={t('models.general.retryMaxAttempts')} value={chat.options?.retryMaxAttempts} />
        <ConfigRow label={t('models.general.sseTimeout')} value={duration(chat.options?.sseTimeoutSeconds)} />
      </SettingsSection>

      {/* ── Основная модель ── */}
      <SettingsSection label={t('models.chat.label')}>
        <div className="set-row">
          <span className="set-row__label">{t('models.chat.model')}</span>
          <span className="set-row__value">{defaultId}</span>
          {chat.defaultModel?.label && chat.defaultModel.label !== 'Default' && (
            <span className="model-row__badge">{chat.defaultModel.label}</span>
          )}
        </div>
      </SettingsSection>

      {chat.models?.length > 0 && (
        <SettingsSection label={t('models.available.label')} rows>
          {chat.models.map((m) => (
            <div key={m.id} className="model-row">
              <span className="model-row__name">{m.id}</span>
              {m.label && m.label !== m.id && <span className="model-row__label">{m.label}</span>}
              {m.id === defaultId && <span className="model-row__badge">{t('models.available.defaultBadge')}</span>}
            </div>
          ))}
        </SettingsSection>
      )}

      {/* ── Агент поиска ── */}
      <SettingsSection label={t('models.searchCodebase.label')}>
        <ConfigStatusRow label={t('models.searchCodebase.status')} on={searchCodebase.enabled} />
        <ConfigRow
          label={t('models.searchCodebase.model')}
          value={subagentSameModel ? t('models.searchCodebase.modelSameAs', { id: defaultId }) : searchCodebase.modelId}
        />
        <ConfigRow
          label={t('models.searchCodebase.maxTokens')}
          value={t('config.tokensValue', { count: searchCodebase.maxTokens.toLocaleString() })}
        />
        <ConfigRow label={t('models.searchCodebase.maxIterations')} value={searchCodebase.maxIterations} />
        <div className="config-block">
          <span className="config-block__label">{t('models.searchCodebase.allowedTools')}</span>
          <ConfigTags items={searchCodebase.allowedTools} empty={t('models.searchCodebase.allowedToolsEmpty')} />
        </div>
      </SettingsSection>

      {/* ── Сжатие контекста ── */}
      <SettingsSection label={t('models.summarize.label')}>
        <ConfigRow label={t('models.summarize.model')} value={t('models.summarize.modelSameAs', { id: defaultId })} />
        <ConfigRow
          label={t('models.summarize.tokenThreshold')}
          value={t('config.tokensValue', { count: summarize.tokenThreshold.toLocaleString() })}
        />
        <ConfigRow
          label={t('models.summarize.messageThreshold')}
          value={t('config.messagesValue', { count: summarize.messageCountThreshold })}
        />
        <ConfigRow
          label={t('models.summarize.overlap')}
          value={t('config.messagesValue', { count: summarize.overlapMessages })}
        />
        <ConfigRow
          label={t('models.summarize.collapseThreshold')}
          value={t('models.summarize.summariesValue', { count: summarize.summaryCollapseThreshold })}
        />
        <ConfigRow
          label={t('models.summarize.charsPerToken')}
          value={t('models.summarize.charsValue', { count: summarize.charsPerToken })}
        />
      </SettingsSection>

      {/* ── Эмбеддинги ── */}
      <SettingsSection label={t('models.embedding.label')}>
        <ConfigRow label={t('models.embedding.model')} value={embedding.model} />
        <ConfigRow
          label={t('models.embedding.chunkMaxTokens')}
          value={t('config.tokensValue', { count: embedding.chunker.maxTokens })}
        />
        <ConfigRow
          label={t('models.embedding.chunkOverlap')}
          value={t('config.tokensValue', { count: embedding.chunker.overlapTokens })}
        />
        <ConfigRow
          label={t('models.embedding.reindexBatchSize')}
          value={t('models.embedding.documentsValue', { count: embedding.reindexBatchSize })}
        />
        <ConfigStatusRow label={t('models.embedding.cache')} on={embedding.cache.enabled} />
        <ConfigRow
          label={t('models.embedding.cacheTtl')}
          value={t('config.daysValue', { count: embedding.cache.ttlDays })}
        />
      </SettingsSection>
    </>
  );
};

export default ModelsSettings;
