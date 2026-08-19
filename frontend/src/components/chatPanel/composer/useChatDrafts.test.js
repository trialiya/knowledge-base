import { renderHook, act } from '@testing-library/react';
import useChatDrafts from './useChatDrafts';
import { STORAGE_KEY_CHAT_DRAFTS, STORAGE_KEY_CHAT_STAGED, DRAFT_CHAT_ID } from '../../../constants/storage';

const ATTACHMENT = { kind: 'ATTACHMENT', ref: '7', label: 'report.md' };

beforeEach(() => localStorage.clear());

describe('useChatDrafts', () => {
  it('stages an item per chat and drops it by kind+ref', () => {
    const { result } = renderHook(() => useChatDrafts());

    act(() => result.current.stageContextItem('c1', ATTACHMENT));
    expect(result.current.getStagedFor('c1')).toEqual([ATTACHMENT]);
    // Другой чат отложенного не наследует.
    expect(result.current.getStagedFor('c2')).toEqual([]);

    // Совпадение ref при другом kind — не тот же элемент.
    act(() => result.current.unstageContextItem('c1', { kind: 'DOC', ref: '7' }));
    expect(result.current.getStagedFor('c1')).toEqual([ATTACHMENT]);

    act(() => result.current.unstageContextItem('c1', { kind: 'ATTACHMENT', ref: '7' }));
    expect(result.current.getStagedFor('c1')).toEqual([]);
  });

  // Чат «new» получает настоящий id, когда файл приложили до первого сообщения:
  // набранный текст и отложенное должны переехать, иначе поле ввода очистится.
  it('moves both the text and the staged items onto the new chat id', () => {
    const { result } = renderHook(() => useChatDrafts());

    act(() => {
      result.current.handleTextChange(DRAFT_CHAT_ID, 'посмотри файл');
      result.current.stageContextItem(DRAFT_CHAT_ID, ATTACHMENT);
    });

    act(() => result.current.moveDraft(DRAFT_CHAT_ID, 'uuid-1'));

    expect(result.current.getDraftFor('uuid-1')).toBe('посмотри файл');
    expect(result.current.getStagedFor('uuid-1')).toEqual([ATTACHMENT]);
    expect(result.current.getDraftFor(DRAFT_CHAT_ID)).toBe('');
    expect(result.current.getStagedFor(DRAFT_CHAT_ID)).toEqual([]);
  });

  it('persists the move so a reload finds the draft under the new id', () => {
    const { result } = renderHook(() => useChatDrafts());

    act(() => {
      result.current.handleTextChange(DRAFT_CHAT_ID, 'посмотри файл');
      result.current.stageContextItem(DRAFT_CHAT_ID, ATTACHMENT);
      result.current.moveDraft(DRAFT_CHAT_ID, 'uuid-1');
    });

    expect(JSON.parse(localStorage.getItem(STORAGE_KEY_CHAT_DRAFTS))).toEqual({ 'uuid-1': 'посмотри файл' });
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY_CHAT_STAGED))).toEqual({ 'uuid-1': [ATTACHMENT] });
  });
});
