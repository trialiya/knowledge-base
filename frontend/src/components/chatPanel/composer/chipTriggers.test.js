import { detectTriggerInText, tokenForItem } from './chipTriggers';

describe('detectTriggerInText', () => {
  it('returns null when there is no trigger', () => {
    expect(detectTriggerInText('')).toBeNull();
    expect(detectTriggerInText('just some text')).toBeNull();
    expect(detectTriggerInText('email a/b/file path')).toBeNull();
  });

  // The Russian synonyms are the same triggers, not rules of their own: whatever a command does,
  // so does its synonym.
  it.each([
    ['/file', 'file'],
    ['/doc', 'doc'],
    ['/файл', 'file'],
    ['/док', 'doc'],
  ])('detects %s with its query and its offset', (command, type) => {
    expect(detectTriggerInText(command)).toEqual({ type, query: '', start: 0 });
    expect(detectTriggerInText(`${command} src/App`)).toEqual({ type, query: 'src/App', start: 0 });
    const before = `hello world ${command} utils`;
    expect(detectTriggerInText(before)).toEqual({ type, query: 'utils', start: before.indexOf(command) });
  });

  it('only triggers at the caret (end of string), not mid-text', () => {
    // A command followed by whitespace + another word is no longer at the caret.
    expect(detectTriggerInText('/file foo bar')).toBeNull();
  });

  it('requires a word boundary before the command', () => {
    // No leading whitespace/start-of-string before /file → not a trigger.
    expect(detectTriggerInText('x/file')).toBeNull();
  });
});

describe('tokenForItem', () => {
  it('builds ref vs content tokens for file items', () => {
    const item = { path: 'src/App.jsx' };
    expect(tokenForItem('file', item, false)).toBe('⟦ref:src/App.jsx⟧');
    expect(tokenForItem('file', item, true)).toBe('⟦file:src/App.jsx⟧');
  });

  it('writes the project the file was found in', () => {
    const item = { path: 'src/App.jsx' };
    expect(tokenForItem('file', item, false, 'kb')).toBe('⟦ref@kb:src/App.jsx⟧');
    expect(tokenForItem('file', item, true, 'kb')).toBe('⟦file@kb:src/App.jsx⟧');
  });

  it('builds ref vs content tokens for doc items', () => {
    const item = { id: 7, title: 'Guide' };
    // Документам проект не приписывается и с ним: база знаний общая.
    expect(tokenForItem('doc', item, false, 'kb')).toBe('⟦docref:7:Guide⟧');
    expect(tokenForItem('doc', item, true, 'kb')).toBe('⟦doc:7:Guide⟧');
  });
});
