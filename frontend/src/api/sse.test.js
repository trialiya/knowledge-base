import { parseBlock, readJsonSseStream, readSseStream } from './sse';

/** Ответ fetch, отдающий заранее заданные чанки — включая разрезанные посередине кадра. */
function sseResponse(chunks) {
  const encoder = new TextEncoder();
  let i = 0;
  return {
    ok: true,
    body: {
      getReader: () => ({
        read: () =>
          Promise.resolve(i < chunks.length ? { done: false, value: encoder.encode(chunks[i++]) } : { done: true }),
        cancel() {},
      }),
    },
  };
}

describe('parseBlock', () => {
  test('склеивает несколько строк data через перевод строки', () => {
    expect(parseBlock('id: 7\ndata: {"a":1,\ndata: "b":2}')).toEqual({
      id: '7',
      data: '{"a":1,\n"b":2}',
    });
  });

  test('переживает CRLF', () => {
    expect(parseBlock('data: hi\r').data).toBe('hi');
  });
});

describe('readSseStream', () => {
  test('кадр, разрезанный между чанками, собирается целиком', async () => {
    const seen = [];
    await readSseStream(sseResponse(['data: one\n\ndata: t', 'wo\n\n']), (d) => seen.push(d));
    expect(seen).toEqual(['one', 'two']);
  });

  test('незавершённый хвост без пустой строки не отдаётся', async () => {
    const seen = [];
    await readSseStream(sseResponse(['data: done\n\ndata: partial']), (d) => seen.push(d));
    expect(seen).toEqual(['done']);
  });
});

describe('readJsonSseStream', () => {
  test('битый кадр пропускается, остальной поток доходит', async () => {
    const seen = [];
    await readJsonSseStream(
      sseResponse(['data: {"type":"PROGRESS"}\n\ndata: {oops\n\ndata: {"type":"DONE"}\n\n']),
      (e) => seen.push(e.type),
    );
    expect(seen).toEqual(['PROGRESS', 'DONE']);
  });
});
