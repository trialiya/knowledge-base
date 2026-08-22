import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import ConfigGroup, {
  ConfigRow,
  ConfigStatusRow,
  ConfigBoolRow,
  ConfigTags,
  ConfigBlock,
  useDurationFormat,
} from '@/components/common/config/ConfigGroup';
import useConfigSnapshot from '@/components/common/config/useConfigSnapshot';
import settingsApi from '@/api/settingsApi';

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
        {/* Строки «Окно памяти» здесь намеренно нет: память чата (ChatHistoryMemory) пишет
            строго дописывая и историю по числу сообщений не режет. Реальный предел контекста —
            пороги суммаризации ниже, в секции «Сжатие контекста». */}
        <ConfigRow label={t('models.general.requestTimeout')} value={duration(chat.options?.requestTimeoutSeconds)} />
        <ConfigRow label={t('models.general.retryMaxAttempts')} value={chat.options?.retryMaxAttempts} />
        <ConfigRow label={t('models.general.sseTimeout')} value={duration(chat.options?.sseTimeoutSeconds)} />
      </SettingsSection>

      {/* ── Основная модель ── */}
      <SettingsSection label={t('models.chat.label')}>
        {/* Метка «Default» — заглушка из ChatModelProperties, а не имя модели:
            показывать её рядом с id нечего. */}
        <ConfigRow
          label={t('models.chat.model')}
          value={defaultId}
          badge={chat.defaultModel?.label !== 'Default' ? chat.defaultModel?.label : null}
        />
        {/* weak — свойство модели, а не деплоя: от него зависит только объём
            руководства по runScript в системном промпте (ScriptGuideService). */}
        <ConfigBoolRow label={t('models.chat.weak')} value={chat.defaultModel?.weak} />
        <p className="config-note">{t('models.chat.weakNote')}</p>
      </SettingsSection>

      {chat.models?.length > 0 && (
        <SettingsSection label={t('models.available.label')} rows>
          {chat.models.map((m) => (
            <div key={m.id} className="model-row">
              <span className="model-row__name">{m.id}</span>
              {m.label && m.label !== m.id && <span className="model-row__label">{m.label}</span>}
              {m.weak && <span className="config-badge">{t('models.available.weakBadge')}</span>}
              {/* Модель отвечает не с общего эндпоинта: у неё свои base-url/api-key
                  (kb.chat.models[].base-url). Сам адрес и токен наружу не отдаются. */}
              {m.ownEndpoint && <span className="config-badge">{t('models.available.ownEndpointBadge')}</span>}
              {m.id === defaultId && <span className="config-badge">{t('models.available.defaultBadge')}</span>}
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
        <ConfigBlock label={t('models.searchCodebase.allowedTools')}>
          <ConfigTags items={searchCodebase.allowedTools} empty={t('models.searchCodebase.allowedToolsEmpty')} />
        </ConfigBlock>
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
        {/* Второе условие того же перекрытия, а не альтернатива ему: живой хвост обязан
            удовлетворять обоим порогам сразу, иначе длинный tool-марафон вытеснил бы
            в сводку последние вопросы пользователя. */}
        <ConfigRow
          label={t('models.summarize.overlapUser')}
          value={t('config.messagesValue', { count: summarize.overlapUserMessages })}
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
