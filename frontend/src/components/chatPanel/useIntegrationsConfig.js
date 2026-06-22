import { useEffect, useState } from 'react';
import settingsApi from '../../api/settingsApi';

/**
 * Один раз проверяет наличие токенов Atlassian (GET /api/settings/integrations),
 * чтобы показывать кнопку «Jira-чат» и поле Confluence в модалке создания.
 *
 * @returns {{ jiraConfigured: boolean, confluenceConfigured: boolean }}
 */
export default function useIntegrationsConfig() {
  const [jiraConfigured, setJiraConfigured] = useState(false);
  const [confluenceConfigured, setConfluenceConfigured] = useState(false);

  useEffect(() => {
    let cancelled = false;
    settingsApi
      .getIntegrations()
      .then((cfg) => {
        if (!cancelled) {
          setJiraConfigured(cfg.jiraConfigured);
          setConfluenceConfigured(cfg.confluenceConfigured);
        }
      })
      .catch((err) => console.error('Ошибка загрузки настроек интеграций:', err));
    return () => {
      cancelled = true;
    };
  }, []);

  return { jiraConfigured, confluenceConfigured };
}
