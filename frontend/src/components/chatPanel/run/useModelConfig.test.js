import { describe, it, expect, beforeEach } from 'vitest';
import { modelForChat } from './useModelConfig';
import { setLastModel } from './lastChoiceStore';

/**
 * Одна модель на селектор и на отправку: стоит на экране одна, а уйти сообщение обязано с ней
 * же — от неё зависят и уровни рассуждений в селекторе.
 */
describe('modelForChat', () => {
  const config = { defaultModel: { id: 'gpt' } };
  const options = [{ id: 'gpt' }, { id: 'deepseek' }];

  beforeEach(() => setLastModel(''));

  it('takes the chat model while the config still has it', () => {
    expect(modelForChat({ model: 'deepseek' }, options, config)).toBe('deepseek');
  });

  it('falls back to the last sent model, then to the default', () => {
    setLastModel('deepseek');
    expect(modelForChat({ model: null }, options, config)).toBe('deepseek');
    expect(modelForChat({ model: 'gone' }, options, config)).toBe('deepseek');
    setLastModel('gone-too');
    expect(modelForChat({ model: null }, options, config)).toBe('gpt');
  });

  it('is null until the config arrives', () => {
    expect(modelForChat({ model: null }, [], null)).toBeNull();
  });
});
