import { useCallback, useState } from 'react';

/**
 * Раздел, к которому вкладка «Разделы» просит прокрутить центр: `{ path, line }`.
 *
 * Новый объект на каждый клик — повторный клик по тому же разделу тоже
 * прокручивает (центр реагирует на смену объекта, а не строки). Путь входит в
 * значение, и чужой путь сбрасывается при рендере: иначе только что открытый
 * файл, смонтировавшись, прокрутился бы к строке, выбранной в прошлом.
 *
 * @returns {{ jump: {path: string, line: number}|null, onJump: (line: number) => void }}
 */
export default function useSectionJump(path) {
  const [jump, setJump] = useState(null);
  if (jump && jump.path !== path) setJump(null);
  const onJump = useCallback((line) => setJump({ path, line }), [path]);
  return { jump: jump?.path === path ? jump : null, onJump };
}
