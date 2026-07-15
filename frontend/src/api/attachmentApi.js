// ─── Attachment API ──────────────────────────────────────────────────────────
// Обёртки вокруг /api/attachments/* и /<type>/<id>/attachments эндпоинтов.
// Documents и chats используют одну форму, различаясь только URL-сегментом.

import { request, requestRaw } from './client';
import { OWNER_TYPE } from '../constants/ownerType';

const seg = (ownerType) => (ownerType === OWNER_TYPE.DOCUMENT ? 'documents' : 'chats');

const attachmentApi = {
  list: (ownerType, ownerId) => request(`/api/${seg(ownerType)}/${ownerId}/attachments`),

  upload: (ownerType, ownerId, file) => {
    const formData = new FormData();
    formData.append('file', file);
    return request(`/api/${seg(ownerType)}/${ownerId}/attachments`, {
      method: 'POST',
      body: formData,
    });
  },

  delete: (id) => requestRaw(`/api/attachments/${id}`, { method: 'DELETE' }),

  summarize: (id) => request(`/api/attachments/${id}/summarize`, { method: 'POST' }),

  getContent: (id) => fetch(`/api/attachments/${id}/content`).then((r) => r.text()),
};

export default attachmentApi;
