// ─── Attachment API helpers ────────────────────────────────────────────────────
// Wrappers around the attachment endpoints. Documents and chats share the same
// shape, differing only in the URL segment (documents | chat).

const ownerSegment = (ownerType) => (ownerType === 'document' ? 'documents' : 'chat');

const attachmentApi = {
  list: (ownerType, ownerId) => fetch(`/api/${ownerSegment(ownerType)}/${ownerId}/attachments`).then((r) => r.json()),

  upload: (ownerType, ownerId, file) => {
    const formData = new FormData();
    formData.append('file', file);
    return fetch(`/api/${ownerSegment(ownerType)}/${ownerId}/attachments`, {
      method: 'POST',
      body: formData,
    }).then((r) => {
      if (!r.ok) throw new Error(`Upload failed: ${r.status}`);
      return r.json();
    });
  },

  delete: (id) => fetch(`/api/attachments/${id}`, { method: 'DELETE' }),

  summarize: (id) => fetch(`/api/attachments/${id}/summarize`, { method: 'POST' }).then((r) => r.json()),

  getContent: (id) => fetch(`/api/attachments/${id}/content`).then((r) => r.text()),
};

/** Human-readable file size. */
export function formatFileSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default attachmentApi;
