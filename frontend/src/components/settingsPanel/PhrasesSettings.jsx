import React, { useState, useEffect, useCallback, useMemo } from 'react';
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
  const [phrases, setPhrases] = useState([]);
  const [query, setQuery] = useState('');
  const [form, setForm] = useState(null); // null = форма скрыта; иначе {id?, category, label, text, enabled}
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [pendingDelete, setPendingDelete] = useState(null); // фраза, ожидающая подтверждения удаления

  const reload = useCallback(
      (q = query) =>
          fetchAllPhrases(q)
              .then((d) => {
                setPhrases(Array.isArray(d) ? d : []);
                setError(null);
              })
              .catch((e) => setError(e.message || 'Не удалось загрузить фразы')),
      [query],
  );

  // первичная загрузка
  useEffect(() => {
    reload('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // дебаунс быстрого поиска
  useEffect(() => {
    const id = setTimeout(() => reload(query), 200);
    return () => clearTimeout(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

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
      setError(e.message || 'Не удалось сохранить фразу');
    } finally {
      setBusy(false);
    }
  };

  // удаление в два шага: клик открывает ConfirmModal, подтверждение выполняет запрос
  const askDelete = (p) => setPendingDelete(p);
  const confirmDelete = () => {
    const p = pendingDelete;
    setPendingDelete(null);
    guard(() => deletePhrase(p.id), 'Не удалось удалить фразу');
  };

  // переключаем только флаг — отдельный PATCH, текст фразы не перетирается
  const toggleEnabled = (p) =>
      guard(() => adminToggleEnabled(p.id, !p.enabled), 'Не удалось изменить статус');
  const toggleFav = (p) =>
      guard(() => adminToggleFavorite(p.id, !p.favorite), 'Не удалось изменить избранное');

  const moveUp = (p) => {
    const arr = neighbors[p.category];
    const i = arr.findIndex((x) => x.id === p.id);
    if (i > 0) guard(() => movePhrase(p.id, arr[i - 1].position), 'Не удалось переместить');
  };

  const moveDown = (p) => {
    const arr = neighbors[p.category];
    const i = arr.findIndex((x) => x.id === p.id);
    if (i >= 0 && i < arr.length - 1)
      guard(() => movePhrase(p.id, arr[i + 1].position), 'Не удалось переместить');
  };

  return (
      <>
        <SettingsContentHead title="Библиотека фраз" subtitle="Готовые подсказки над полем ввода в пустом чате" />
        <div className="settings-content__body">
          {error && <p className="phrase-error" role="alert">{error}</p>}

          <SettingsSection
              label="Фразы"
              action={
                <button className="set-btn set-btn--ghost set-btn--sm" onClick={startCreate}>
                  <IconPlus /> Добавить
                </button>
              }
              rows
          >
            <div className="phrase-search">
              <input
                  className="phrase-input"
                  placeholder="Поиск по названию…"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
              />
            </div>

            {form && (
                <div className="phrase-form">
                  <div className="phrase-form__grid">
                    <input
                        className="phrase-input"
                        placeholder="Категория"
                        value={form.category}
                        onChange={(e) => setForm({ ...form, category: e.target.value })}
                    />
                    <input
                        className="phrase-input"
                        placeholder="Название (чип)"
                        value={form.label}
                        onChange={(e) => setForm({ ...form, label: e.target.value })}
                    />
                  </div>
                  <textarea
                      className="set-textarea"
                      rows={3}
                      placeholder="Текст, вставляемый в поле ввода. Плейсхолдеры {{...}} правятся вручную."
                      value={form.text}
                      onChange={(e) => setForm({ ...form, text: e.target.value })}
                  />
                  <label className="phrase-form__check">
                    <input
                        type="checkbox"
                        checked={form.enabled}
                        onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
                    />
                    Включена
                  </label>
                  <div className="set-actions">
                    <button className="set-btn set-btn--primary" onClick={save} disabled={busy}>
                      Сохранить
                    </button>
                    <button className="set-btn set-btn--ghost" onClick={cancel} disabled={busy}>
                      Отмена
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
                      <button className="set-icon-btn" title="Выше" disabled={i <= 0} onClick={() => moveUp(p)}>
                        ↑
                      </button>
                      <button
                          className="set-icon-btn"
                          title="Ниже"
                          disabled={i < 0 || i >= arr.length - 1}
                          onClick={() => moveDown(p)}
                      >
                        ↓
                      </button>
                    </div>

                    <button
                        className={`set-icon-btn ${p.favorite ? 'set-icon-btn--star' : ''}`}
                        title={p.favorite ? 'Убрать из избранного' : 'В избранное'}
                        onClick={() => toggleFav(p)}
                    >
                      {p.favorite ? '★' : '☆'}
                    </button>
                    <button
                        className="set-icon-btn"
                        title={p.enabled ? 'Выключить' : 'Включить'}
                        onClick={() => toggleEnabled(p)}
                    >
                      {p.enabled ? '◉' : '○'}
                    </button>
                    <button className="set-icon-btn" title="Редактировать" onClick={() => startEdit(p)}>
                      <IconEdit />
                    </button>
                    <button
                        className="set-icon-btn set-icon-btn--danger"
                        title="Удалить"
                        onClick={() => askDelete(p)}
                    >
                      <IconTrash />
                    </button>
                  </div>
              );
            })}
          </SettingsSection>

          <p className="set-hint">
            Раньше фразы жили в коде (<code>GIT_PHRASES</code>). Теперь — единый список из БД: правится здесь,
            показывается в пустом чате компонентом <code>Phrases</code> (<code>GET /api/phrases</code>).
          </p>
        </div>

        <ConfirmModal
            open={!!pendingDelete}
            icon="🗑️"
            title="Удалить фразу?"
            message={
              pendingDelete
              ? `«${pendingDelete.label}» из категории «${pendingDelete.category}» будет удалена безвозвратно.`
              : ''
            }
            confirmLabel="Удалить"
            onConfirm={confirmDelete}
            onCancel={() => setPendingDelete(null)}
        />
      </>
  );
};

export default PhrasesSettings;