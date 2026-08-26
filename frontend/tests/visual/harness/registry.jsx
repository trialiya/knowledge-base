import ChatRepoPanel from '@/components/chatPanel/git/ChatRepoPanel';
import GitCommandsModal from '@/components/chatPanel/git/GitCommandsModal';
import GitOutputCard from '@/components/chatPanel/git/GitOutputCard';
import GitBranchBar from '@/components/filesPanel/git/GitBranchBar';
import * as chatRepo from '../fixtures/chatRepo';
import * as gitMenu from '../fixtures/gitMenu';

/**
 * Что стенд умеет показать: фикстура из `../fixtures/` плюс компонент, которым
 * её рисуют, плюс рамка, в которой этот компонент живёт в приложении.
 *
 * Список явный, а не выведенный из `cases.yaml`: не всякая фикстура рисуется
 * в одиночку — часть кейсов про целые экраны, и им нужен поднятый бэкенд
 * (`./run/test.sh smoke`). Здесь только то, чему хватает пропсов.
 *
 * `id` совпадает с ссылкой на фикстуру в `cases.yaml` — `<модуль>#<экспорт>`,
 * — чтобы кейс и снимок находились друг по другу без второго имени.
 *
 * `frame` — во что обернуть:
 *   `panel` — колонка правой панели, 320px (иначе компонент растечётся на всё
 *             окно, и всё, что решает ширина, окажется непроверенным);
 *   `feed`  — колонка ленты чата: она flex, и элемент без `flex: none` в ней
 *             схлопывается — ровно так однажды пропала карточка вывода;
 *   `bare`  — сам себе рамка (модалка: у неё свой оверлей на всё окно);
 *   `left`  — колонка левой панели и центр рядом с ней: её выпадающие списки
 *             уходят порталом поверх центра, и без него не видно ни куда они
 *             попадают, ни сколько места им осталось.
 *
 * `click` — что щёлкнуть перед снимком: состояние, которое компонент открывает
 * сам (меню), пропсами не задаётся вовсе.
 */
const REGISTRY = [
  { id: 'chatRepo.js#repoTabIdle', frame: 'panel', render: (p) => <ChatRepoPanel {...p} /> },
  { id: 'chatRepo.js#repoTabBusy', frame: 'panel', render: (p) => <ChatRepoPanel {...p} /> },
  { id: 'chatRepo.js#repoTabMerging', frame: 'panel', render: (p) => <ChatRepoPanel {...p} /> },
  { id: 'chatRepo.js#commandsModalDirty', frame: 'bare', render: (p) => <GitCommandsModal {...p} /> },
  { id: 'chatRepo.js#commandsModalMerging', frame: 'bare', render: (p) => <GitCommandsModal {...p} /> },
  { id: 'chatRepo.js#commandsModalBusy', frame: 'bare', render: (p) => <GitCommandsModal {...p} /> },
  { id: 'chatRepo.js#outputCardOk', frame: 'feed', render: (p) => <GitOutputCard {...p} /> },
  { id: 'chatRepo.js#outputCardRefused', frame: 'feed', render: (p) => <GitOutputCard {...p} /> },
  { id: 'chatRepo.js#outputCardSilent', frame: 'feed', render: (p) => <GitOutputCard {...p} /> },
  {
    id: 'gitMenu.js#branchMenuLongNames',
    frame: 'left',
    click: '.git-menu .icon-btn',
    render: (p) => <GitBranchBar {...p} />,
  },
];

const MODULES = { 'chatRepo.js': chatRepo, 'gitMenu.js': gitMenu };

/** Все кейсы стенда с уже разрешёнными пропсами. Незнакомый экспорт — ошибка сборки кейса. */
export const cases = REGISTRY.map((entry) => {
  const [module, exportName] = entry.id.split('#');
  const props = MODULES[module]?.[exportName];
  return { ...entry, props, missing: !props };
});

export const findCase = (id) => cases.find((c) => c.id === id);
