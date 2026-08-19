import { describe, it, expect } from 'vitest';
import { markUnavailable, resolveProjectChoice } from './projectChoice';

const OPTIONS = [
  { id: 'docs', label: 'Docs' },
  { id: 'api', label: 'API' },
];

describe('resolveProjectChoice', () => {
  it('keeps a project that is in the list', () => {
    expect(resolveProjectChoice('api', OPTIONS, 'docs')).toEqual({ selected: 'api', missing: null });
  });

  it('falls back to the default when the project is gone, and names it', () => {
    expect(resolveProjectChoice('old', OPTIONS, 'docs')).toEqual({ selected: 'docs', missing: 'old' });
  });

  it('trusts the project while the list is empty — that is «no list», not «no such project»', () => {
    expect(resolveProjectChoice('api', [], 'docs')).toEqual({ selected: 'api', missing: null });
  });

  it('resolves an unnamed project to the default without a warning', () => {
    expect(resolveProjectChoice(null, OPTIONS, 'docs')).toEqual({ selected: 'docs', missing: null });
  });

  it('yields an empty selection when there is no default either', () => {
    expect(resolveProjectChoice(null, [], null)).toEqual({ selected: '', missing: null });
  });
});

describe('markUnavailable', () => {
  it('marks a project whose repository did not open, and leaves the rest untouched', () => {
    const options = [
      { id: 'docs', label: 'Docs', available: true },
      { id: 'api', label: 'API', available: false },
    ];

    expect(markUnavailable(options, 'недоступен')).toEqual([
      { id: 'docs', label: 'Docs', available: true },
      { id: 'api', label: 'API', available: false, note: '(недоступен)' },
    ]);
  });

  it('marks nothing when the field is absent — an old backend says nothing about availability', () => {
    const options = [{ id: 'docs', label: 'Docs' }];

    expect(markUnavailable(options, 'недоступен')).toEqual(options);
  });
});
