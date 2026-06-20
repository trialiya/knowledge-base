import { request } from './client';

const settingsApi = {
  /** AI configuration snapshot: chat models, embedding, searchCodebase, summarize. */
  getAiConfig: () => request('/api/settings/ai-config'),

  /** Returns { jiraConfigured, confluenceConfigured } based on server-side token presence. */
  getIntegrations: () => request('/api/settings/integrations'),
};

export default settingsApi;
