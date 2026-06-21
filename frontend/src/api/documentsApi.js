import { request } from './client';

const documentsApi = {
  searchByName: (name, limit = 10, signal) => {
    const params = new URLSearchParams({ name, limit: String(limit) });
    return request(`/api/documents/search-by-name?${params}`, signal ? { signal } : undefined);
  },
  getById: (id, signal) => {
    return request(`/api/documents/${id}`, signal ? { signal } : undefined);
  },
};

export default documentsApi;
