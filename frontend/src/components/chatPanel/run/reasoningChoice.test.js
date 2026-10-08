import { describe, it, expect } from 'vitest';
import { DEFAULT_REASONING, effectiveReasoning, reasoningForSend, reasoningOf } from './reasoningChoice';

const config = {
  defaultModel: {
    id: 'gpt-5',
    label: 'GPT-5',
    reasoning: { default: 'medium', levels: [{ id: 'low' }, { id: 'medium' }, { id: 'high' }] },
  },
  models: [
    { id: 'gpt-5', label: 'GPT-5', reasoning: { default: 'medium', levels: [{ id: 'low' }, { id: 'medium' }] } },
    { id: 'deepseek', label: 'DeepSeek', reasoning: { default: null, levels: [{ id: 'off' }, { id: 'on' }] } },
    { id: 'plain', label: 'Plain', reasoning: null },
  ],
};

describe('reasoningOf', () => {
  it('reads the default model by its own entry, not the list copy', () => {
    expect(reasoningOf(config, null).levels).toHaveLength(3);
    expect(reasoningOf(config, 'gpt-5').levels).toHaveLength(3);
  });

  it('gives no levels to a model without them, an unknown model or a missing config', () => {
    expect(reasoningOf(config, 'plain')).toBeNull();
    expect(reasoningOf(config, 'gone')).toBeNull();
    expect(reasoningOf(null, 'gpt-5')).toBeNull();
  });
});

describe('effectiveReasoning', () => {
  it('keeps a choice the model has', () => {
    expect(effectiveReasoning(reasoningOf(config, 'gpt-5'), 'high')).toBe('high');
  });

  it('falls back to the model default, then to "default" when the model has none', () => {
    expect(effectiveReasoning(reasoningOf(config, 'gpt-5'), 'on')).toBe('medium');
    expect(effectiveReasoning(reasoningOf(config, 'deepseek'), 'high')).toBe(DEFAULT_REASONING);
    expect(effectiveReasoning(reasoningOf(config, 'deepseek'), null)).toBe(DEFAULT_REASONING);
  });
});

describe('reasoningForSend', () => {
  it('sends the choice only when the model has it', () => {
    expect(reasoningForSend(config, 'deepseek', 'on')).toBe('on');
    // The model default is never sent explicitly: the chat keeps its own choice for the next model.
    expect(reasoningForSend(config, 'deepseek', 'high')).toBeNull();
    expect(reasoningForSend(config, 'plain', 'high')).toBeNull();
    // No choice in the chat is an explicit reset: it travels with the message, not only with the PUT.
    expect(reasoningForSend(config, 'gpt-5', '')).toBe(DEFAULT_REASONING);
    expect(reasoningForSend(config, 'plain', null)).toBe(DEFAULT_REASONING);
  });
});
