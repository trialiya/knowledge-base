import { request } from './client';

const settingsApi = {
  /** AI configuration snapshot: chat models, embedding, searchCodebase, summarize, search, tools. */
  getAiConfig: () => request('/api/settings/ai-config'),

  /** Server-side snapshot for the admin panel: app, database, git, documents, indexing queue. */
  getSystemInfo: () => request('/api/admin/system'),
};

export default settingsApi;
