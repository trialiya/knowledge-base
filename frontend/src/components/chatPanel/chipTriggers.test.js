import { detectTriggerInText, tokenForItem, TRIGGER_TYPES } from './chipTriggers';

describe('detectTriggerInText', () => {
  it('returns null when there is no trigger', () => {
    expect(detectTriggerInText('')).toBeNull();
    expect(detectTriggerInText('just some text')).toBeNull();
    expect(detectTriggerInText('email a/b/file path')).toBeNull();
  });

  it('detects a /file trigger at the start of the text', () => {
    expect(detectTriggerInText('/file')).toEqual({ type: 'file', query: '', start: 0 });
  });

  it('detects a /doc trigger at the start of the text', () => {
    expect(detectTriggerInText('/doc')).toEqual({ type: 'doc', query: '', start: 0 });
  });

  it('extracts the query following the command', () => {
    expect(detectTriggerInText('/file src/App')).toEqual({ type: 'file', query: 'src/App', start: 0 });
    expect(detectTriggerInText('/doc 42')).toEqual({ type: 'doc', query: '42', start: 0 });
  });

  it('reports the command start offset when preceded by text', () => {
    const before = 'hello world /file utils';
    const hit = detectTriggerInText(before);
    expect(hit).toEqual({ type: 'file', query: 'utils', start: before.indexOf('/file') });
  });

  it('only triggers at the caret (end of string), not mid-text', () => {
    // A command followed by whitespace + another word is no longer at the caret.
    expect(detectTriggerInText('/file foo bar')).toBeNull();
  });

  it('requires a word boundary before the command', () => {
    // No leading whitespace/start-of-string before /file → not a trigger.
    expect(detectTriggerInText('x/file')).toBeNull();
  });

  it('detects the /файл synonym for the file trigger', () => {
    expect(detectTriggerInText('/файл')).toEqual({ type: 'file', query: '', start: 0 });
    expect(detectTriggerInText('/файл src/App')).toEqual({ type: 'file', query: 'src/App', start: 0 });
    const before = 'hello world /файл utils';
    expect(detectTriggerInText(before)).toEqual({ type: 'file', query: 'utils', start: before.indexOf('/файл') });
  });

  it('detects the /док synonym for the doc trigger', () => {
    expect(detectTriggerInText('/док')).toEqual({ type: 'doc', query: '', start: 0 });
    expect(detectTriggerInText('/док 42')).toEqual({ type: 'doc', query: '42', start: 0 });
  });
});

describe('tokenForItem', () => {
  it('builds ref vs content tokens for file items', () => {
    const item = { path: 'src/App.jsx' };
    expect(tokenForItem('file', item, false)).toBe('⟦ref:src/App.jsx⟧');
    expect(tokenForItem('file', item, true)).toBe('⟦file:src/App.jsx⟧');
  });

  it('builds ref vs content tokens for doc items', () => {
    const item = { id: 7, title: 'Guide' };
    expect(tokenForItem('doc', item, false)).toBe('⟦docref:7:Guide⟧');
    expect(tokenForItem('doc', item, true)).toBe('⟦doc:7:Guide⟧');
  });
});

describe('TRIGGER_TYPES', () => {
  it('exposes a self-describing spec per type', () => {
    for (const key of ['file', 'doc']) {
      const spec = TRIGGER_TYPES[key];
      expect(spec.type).toBe(key);
      expect(spec.triggers[0]).toBe(`/${key}`);
      expect(typeof spec.search).toBe('function');
      expect(typeof spec.refToken).toBe('function');
      expect(typeof spec.contentToken).toBe('function');
    }
  });
});
