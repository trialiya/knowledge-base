import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsSection } from '@/components/common/layout/SettingsShell';
import { ConfigTags } from '@/components/common/config/ConfigGroup';
import ListboxSelect from '@/components/common/ui/ListboxSelect';
import useConfigSnapshot from '@/components/common/config/useConfigSnapshot';
import { getToolIcon, toolLabelKey, humanizeTool } from '@/components/common/ui/toolNames';
import settingsApi from '@/api/settingsApi';

/**
 * Каталог инструментов, доступных модели: выпадашка со списком, под ней —
 * карточка выбранного (описание и аргументы). Список приходит с бэкенда из тех
 * же callbacks, что отданы чату (`GET /api/settings/tools`), поэтому отражает
 * фактическую конфигурацию: без `kb.projects[].edit-enabled` в нём нет createFile, без
 * MCP-сервера — его инструментов.
 *
 * Выпадашка, а не список из сорока карточек: описание инструмента — это кусок
 * промпта на несколько абзацев, и читают их по одному.
 */
const ToolCatalog = () => {
  const { t } = useTranslation(['settings', 'chat']);
  const { data: tools, error } = useConfigSnapshot(settingsApi.getTools);
  const [chosen, setChosen] = useState(null);

  const options = useMemo(
    () =>
      (tools || [])
        .map((tool) => ({
          id: tool.name,
          label: t(`chat:${toolLabelKey(tool.name)}`, { defaultValue: humanizeTool(tool.name) }),
          note: tool.name,
        }))
        .sort((a, b) => a.label.localeCompare(b.label)),
    [tools, t],
  );

  // Выбранный инструмент выводится, а не хранится: до первого выбора показываем
  // первый пункт списка, а имя из state перестаёт что-либо значить, если каталог
  // успел смениться (сервер перезапущен с другой конфигурацией).
  const byName = (name) => tools?.find((tool) => tool.name === name) ?? null;
  const selected = byName(chosen) ?? byName(options[0]?.id);

  const label = tools ? `${t('tools.catalog.label')} · ${tools.length}` : t('tools.catalog.label');

  return (
    <SettingsSection
      label={label}
      overflow
      action={
        options.length > 0 && (
          <ListboxSelect
            value={selected?.name}
            options={options}
            onChange={setChosen}
            ariaLabel={t('tools.catalog.aria')}
          />
        )
      }
    >
      {error && <p className="config-group__error">{error.message || t('config.errorLoading')}</p>}
      {!error && !tools && <p className="config-group__loading">{t('config.loading')}</p>}
      {tools && tools.length === 0 && <p className="config-note">{t('tools.catalog.empty')}</p>}
      {selected && <ToolCard tool={selected} />}
    </SettingsSection>
  );
};

/** Выбранный инструмент: чем он представлен модели — описание и аргументы схемы. */
const ToolCard = ({ tool }) => {
  const { t } = useTranslation(['settings', 'chat']);

  return (
    <>
      <div className="tool-card__head">
        <span className="tool-card__icon">{getToolIcon(tool.name)}</span>
        <span className="tool-card__title">
          {t(`chat:${toolLabelKey(tool.name)}`, { defaultValue: humanizeTool(tool.name) })}
        </span>
        <code className="tool-card__name">{tool.name}</code>
        {tool.origin === 'mcp' && <span className="config-badge">{t('tools.catalog.mcp')}</span>}
      </div>

      <p className="tool-card__desc">{tool.description}</p>

      <div className="config-block">
        <span className="config-block__label">{t('tools.catalog.params')}</span>
        {tool.params.length === 0 ? (
          <span className="config-tags__empty">{t('tools.catalog.noParams')}</span>
        ) : (
          <ul className="tool-params">
            {tool.params.map((param) => (
              <li key={param.name} className="tool-param">
                <div className="tool-param__head">
                  <code className="tool-param__name">{param.name}</code>
                  <span className="tool-param__type">{param.type}</span>
                  <span className={`status-badge status-badge--${param.required ? 'on' : 'off'}`}>
                    {param.required ? t('tools.catalog.required') : t('tools.catalog.optional')}
                  </span>
                </div>
                {param.description && <p className="tool-param__desc">{param.description}</p>}
                {param.values.length > 0 && <ConfigTags items={param.values} />}
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
  );
};

export default ToolCatalog;
