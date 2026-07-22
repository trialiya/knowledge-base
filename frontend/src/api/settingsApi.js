import { request } from './client';

const settingsApi = {
  /** AI configuration snapshot: chat models, embedding, searchCodebase, summarize. */
  getAiConfig: () => request('/api/settings/ai-config'),
};

export default settingsApi;
