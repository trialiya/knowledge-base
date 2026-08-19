import { describe, it, expect } from 'vitest';
import {
  makeToken,
  makeRefToken,
  makeCommitToken,
  parseToken,
  parseCommitToken,
  parseDocToken,
  stampChipProject,
  chipLabel,
  TOKEN_RE,
} from './fileChips';

describe('project in a chip token', () => {
  it('round-trips a file token with its project', () => {
    const t = makeToken('src/App.jsx', { project: 'kb' });
    expect(t).toBe('⟦file@kb:src/App.jsx⟧');
    expect(parseToken(t)).toEqual({ project: 'kb', path: 'src/App.jsx', from: null, to: null, refOnly: false });
  });

  it('round-trips a range and a ref', () => {
    expect(parseToken(makeToken('a.js', { from: 10, to: 20, project: 'kb' }))).toEqual({
      project: 'kb',
      path: 'a.js',
      from: 10,
      to: 20,
      refOnly: false,
    });
    expect(parseToken(makeRefToken('a.js', 'billing'))).toEqual({
      project: 'billing',
      path: 'a.js',
      from: null,
      to: null,
      refOnly: true,
    });
  });

  it('round-trips a commit token', () => {
    const t = makeCommitToken('abc1234', 'Fix the thing', 'billing');
    expect(parseCommitToken(t)).toEqual({ project: 'billing', hash: 'abc1234', subject: 'Fix the thing' });
  });

  it('reads the old unnamed form as «the chat’s project»', () => {
    expect(parseToken('⟦file:src/App.jsx⟧').project).toBeNull();
    expect(parseCommitToken('⟦commit:abc1234:Fix⟧').project).toBeNull();
  });

  it('keeps a colon in the path out of the project slot', () => {
    // Двоеточие в пути законно, и именно поэтому проект отделён «@», а не «:».
    expect(parseToken('⟦file:docs:notes.md⟧')).toMatchObject({ project: null, path: 'docs:notes.md' });
  });

  it('matches both forms with the global matcher', () => {
    const text = 'a ⟦file:old.js⟧ b ⟦ref@kb:new.js⟧ c ⟦doc:7:Гайд⟧';
    expect(text.match(TOKEN_RE)).toEqual(['⟦file:old.js⟧', '⟦ref@kb:new.js⟧', '⟦doc:7:Гайд⟧']);
  });
});

describe('stampChipProject', () => {
  it('names the project in chips that did not', () => {
    expect(stampChipProject('⟦file:a.js⟧ и ⟦ref:b.js⟧ и ⟦commit:abc:Fix⟧', 'kb')).toBe(
      '⟦file@kb:a.js⟧ и ⟦ref@kb:b.js⟧ и ⟦commit@kb:abc:Fix⟧',
    );
  });

  it('leaves already named chips as they are', () => {
    const text = '⟦file@billing:a.js⟧';
    expect(stampChipProject(text, 'kb')).toBe(text);
  });

  it('leaves document chips alone — the knowledge base is shared by all projects', () => {
    const text = '⟦doc:7:Гайд⟧ ⟦docref:7:Гайд⟧';
    expect(stampChipProject(text, 'kb')).toBe(text);
    expect(parseDocToken('⟦doc:7:Гайд⟧')).toEqual({ id: 7, title: 'Гайд' });
  });

  it('does nothing without a project or a draft', () => {
    expect(stampChipProject('⟦file:a.js⟧', '')).toBe('⟦file:a.js⟧');
    expect(stampChipProject('', 'kb')).toBe('');
  });
});

describe('chipLabel', () => {
  it('names only a foreign project', () => {
    expect(chipLabel('billing', 'kb', 'pom.xml')).toBe('billing · pom.xml');
    expect(chipLabel('kb', 'kb', 'pom.xml')).toBe('pom.xml');
    expect(chipLabel(null, 'kb', 'pom.xml')).toBe('pom.xml');
  });
});
