import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { SettingsContentHead, SettingsSection } from '../common/SettingsShell';
import { IconPlus, IconEdit, IconTrash } from '../knowledgeBasePanel/icons';
import ConfirmModal from '../common/ConfirmModal';
import {
  fetchAllPhrases,
  createPhrase,
  updatePhrase,
  deletePhrase,
  adminToggleFavorite,
  adminToggleEnabled,
  movePhrase,
} from '../chatPanel/phrasesApi';

const EMPTY_FORM = { category: '', label: '', text: '', enabled: true };

/**
 * Админская вкладка библиотеки фраз. Полный CRUD + переключение enabled/favorite,
 * переупорядочивание стрелками (windowed move внутри категории) и быстрый поиск по названию.
 * Источник данных — /api/admin/phrases (видит и выключенные).
 */
const PhrasesSettings = () => {
  const { t } = useTranslation('settings');
  const [phrases, setPhrases] = useState([]);
  const [query, setQuery] = useState('');
  const [form, setForm] = useState(null); // null = форма скрыта; иначе {id?, category, label, text, enabled}
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [pendingDelete, setPendingDelete] = useState(null); // фраза, ожидающая подтверждения удаления

  // Ref зеркалит query, чтобы reload не зависел от него в deps и оставался стабильным.
  // Это предотвращает каскадное пересоздание guard и всех экшн-хендлеров при каждом
  // нажатии клавиши в поисковой строке.
  const queryRef = useRef(query);
  useEffect(() => {
    queryRef.current = query;
  }, [query]);

  const reload = useCallback(
    (q) =>
      fetchAllPhrases(q !== undefined ? q : queryRef.current)
        .then((d) => {
          setPhrases(Array.isArray(d) ? d : []);
          setError(null);
        })
        .catch((e) => setError(e.message || t('phrases.errors.load'))),
    [t],
  );

  // первичная загрузка
  useEffect(() => {
    reload('');
  }, [reload]);

  // дебаунс быстрого поиска — пропускаем первый рендер, чтобы не дублировать
  // запрос с первичной загрузкой выше.
  const skipFirstDebounce = useRef(true);
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const id = setTimeout(() => reload(), 200);
    return () => clearTimeout(id);
  }, [query, reload]);

  // соседи внутри категории — для стрелок ↑/↓ (передаём позицию соседа)
  const neighbors = useMemo(() => {
    const byCat = {};
    phrases.forEach((p) => {
      (byCat[p.category] ||= []).push(p);
    });
    Object.values(byCat).forEach((arr) => arr.sort((a, b) => a.position - b.position));
    return byCat;
  }, [phrases]);

  // обёртка для мутаций: гасит ошибку, выполняет действие, перезагружает список,
  // а при сбое (сеть/HTTP) показывает баннер вместо «тихого» падения промиса.
  const guard = useCallback(
    async (fn, fallback) => {
      setError(null);
      try {
        await fn();
        await reload();
      } catch (e) {
        setError(e.message || fallback);
      }
    },
    [reload],
  );

  const startCreate = () => setForm({ ...EMPTY_FORM });
  const startEdit = (p) =>
    setForm({ id: p.id, category: p.category, label: p.label, text: p.text, enabled: p.enabled });
  const cancel = () => setForm(null);

  const save = async () => {
    if (!form.category.trim() || !form.label.trim() || !form.text.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const body = {
        category: form.category.trim(),
        label: form.label.trim(),
        text: form.text.trim(),
        enabled: form.enabled,
      };
      if (form.id) await updatePhrase(form.id, body);
      else await createPhrase(body);
      setForm(null);
      await reload();
    } catch (e) {
      setError(e.message || t('phrases.errors.save'));
    } finally {
      setBusy(false);
    }
  };

  // удаление в два шага: клик открывает ConfirmModal, подтверждение выполняет запрос
  const askDelete = (p) => setPendingDelete(p);
  const confirmDelete = () => {
    const p = pendingDelete;
    setPendingDelete(null);
    guard(() => deletePhrase(p.id), t('phrases.errors.delete'));
  };

  // переключаем только флаг — отдельный PATCH, текст фразы не перетирается
  const toggleEnabled = (p) => guard(() => adminToggleEnabled(p.id, !p.enabled), t('phrases.errors.toggleStatus'));
  const toggleFav = (p) => guard(() => adminToggleFavorite(p.id, !p.favorite), t('phrases.errors.toggleFavorite'));

  const moveUp = (p) => {
    const arr = neighbors[p.category];
    const i = arr.findIndex((x) => x.id === p.id);
    if (i > 0) guard(() => movePhrase(p.id, arr[i - 1].position), t('phrases.errors.move'));
  };

  const moveDown = (p) => {
    const arr = neighbors[p.category];
    const i = arr.findIndex((x) => x.id === p.id);
    if (i >= 0 && i < arr.length - 1) guard(() => movePhrase(p.id, arr[i + 1].position), t('phrases.errors.move'));
  };

  return (
    <>
      <SettingsContentHead title={t('phrases.title')} subtitle={t('phrases.subtitle')} />
      <div className="settings-content__body">
        {error && (
          <p className="phrase-error" role="alert">
            {error}
          </p>
        )}

        <SettingsSection
          label={t('phrases.sectionLabel')}
          action={
            <button className="set-btn set-btn--ghost set-btn--sm" onClick={startCreate}>
              <IconPlus /> {t('phrases.add')}
            </button>
          }
          rows
        >
          <div className="phrase-search">
            <input
              className="phrase-input"
              placeholder={t('phrases.searchPlaceholder')}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>

          {form && (
            <div className="phrase-form">
              <div className="phrase-form__grid">
                <input
                  className="phrase-input"
                  placeholder={t('phrases.form.categoryPlaceholder')}
                  value={form.category}
                  onChange={(e) => setForm({ ...form, category: e.target.value })}
                />
                <input
                  className="phrase-input"
                  placeholder={t('phrases.form.labelPlaceholder')}
                  value={form.label}
                  onChange={(e) => setForm({ ...form, label: e.target.value })}
                />
              </div>
              <textarea
                className="set-textarea"
                rows={3}
                placeholder={t('phrases.form.textPlaceholder')}
                value={form.text}
                onChange={(e) => setForm({ ...form, text: e.target.value })}
              />
              <label className="phrase-form__check">
                <input
                  type="checkbox"
                  checked={form.enabled}
                  onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                />
                {t('phrases.form.enabled')}
              </label>
              <div className="set-actions">
                <button className="set-btn set-btn--primary" onClick={save} disabled={busy}>
                  {t('phrases.form.save')}
                </button>
                <button className="set-btn set-btn--ghost" onClick={cancel} disabled={busy}>
                  {t('phrases.form.cancel')}
                </button>
              </div>
            </div>
          )}

          {phrases.map((p) => {
            const arr = neighbors[p.category] || [];
            const i = arr.findIndex((x) => x.id === p.id);
            return (
              <div key={p.id} className={`phrase-row ${p.enabled ? '' : 'phrase-row--off'}`}>
                <span className="phrase-pill">{p.category}</span>
                <div className="phrase-row__text">
                  <div className="phrase-row__label">{p.label}</div>
                  <div className="phrase-row__body">{p.text}</div>
                </div>

                <div className="phrase-row__order">
                  <button
                    className="set-icon-btn"
                    title={t('phrases.row.moveUp')}
                    disabled={i <= 0}
                    onClick={() => moveUp(p)}
                  >
                    ↑
                  </button>
                  <button
                    className="set-icon-btn"
                    title={t('phrases.row.moveDown')}
                    disabled={i < 0 || i >= arr.length - 1}
                    onClick={() => moveDown(p)}
                  >
                    ↓
                  </button>
                </div>

                <button
                  className={`set-icon-btn ${p.favorite ? 'set-icon-btn--star' : ''}`}
                  title={p.favorite ? t('phrases.row.removeFavorite') : t('phrases.row.addFavorite')}
                  onClick={() => toggleFav(p)}
                >
                  {p.favorite ? '★' : '☆'}
                </button>
                <button
                  className="set-icon-btn"
                  title={p.enabled ? t('phrases.row.disable') : t('phrases.row.enable')}
                  onClick={() => toggleEnabled(p)}
                >
                  {p.enabled ? '◉' : '○'}
                </button>
                <button className="set-icon-btn" title={t('phrases.row.edit')} onClick={() => startEdit(p)}>
                  <IconEdit />
                </button>
                <button
                  className="set-icon-btn set-icon-btn--danger"
                  title={t('phrases.row.delete')}
                  onClick={() => askDelete(p)}
                >
                  <IconTrash />
                </button>
              </div>
            );
          })}
        </SettingsSection>
      </div>

      <ConfirmModal
        open={!!pendingDelete}
        icon="🗑️"
        title={t('phrases.deleteModal.title')}
        message={
          pendingDelete
            ? t('phrases.deleteModal.message', { label: pendingDelete.label, category: pendingDelete.category })
            : ''
        }
        confirmLabel={t('phrases.deleteModal.confirm')}
        onConfirm={confirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </>
  );
};

export default PhrasesSettings;
