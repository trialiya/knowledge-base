import { describe, it, expect } from 'vitest';
import { countProjectBoundTokens, dropProjectBoundTokens } from './fileChips';

const FILE = '⟦file:src/App.jsx⟧';
const RANGE = '⟦file:src/App.jsx#10-20⟧';
const REF = '⟦ref:pom.xml⟧';
const COMMIT = '⟦commit:abc1234:Fix the thing⟧';
const DOC = '⟦doc:7:Архитектура⟧';
const DOCREF = '⟦docref:7:Архитектура⟧';

describe('project-bound chips', () => {
  it('counts file, range, ref and commit chips', () => {
    expect(countProjectBoundTokens(`${FILE} ${RANGE} ${REF} ${COMMIT}`)).toBe(4);
  });

  it('leaves document chips alone — the knowledge base is shared by all projects', () => {
    expect(countProjectBoundTokens(`${DOC} ${DOCREF}`)).toBe(0);
    expect(dropProjectBoundTokens(`${DOC} ${DOCREF}`)).toBe(`${DOC} ${DOCREF}`);
  });

  it('drops the repository chips and keeps the typed text', () => {
    expect(dropProjectBoundTokens(`смотри ${FILE} и ${COMMIT}, что скажешь?`)).toBe('смотри  и , что скажешь?');
  });

  it('handles an empty draft', () => {
    expect(countProjectBoundTokens('')).toBe(0);
    expect(dropProjectBoundTokens('')).toBe('');
  });
});
