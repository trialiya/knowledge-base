import ReactDOM from 'react-dom/client';
import '@/index.css';
// Раскладка — та же, что у приложения: ширину правой панели и отступы её тела
// задаёт она (`--ws-right-width`), и без неё «панель» растеклась бы на всё окно.
import '@/components/common/layout/workspaceLayout.css';
import { i18nReady } from '@/i18n/index';
import { cases, findCase } from './registry';
// Барели стилей — ПОСЛЕ компонентов, как в приложении: там их подтягивают
// панели разделов, то есть позже общих `common/`. Порядок здесь не косметика.
// `.modal-shell` и `.git-commands` задают ширину одинаковой специфичностью
// (0,1,0), и кто из них выиграет, решает порядок в бандле: подняв барель
// наверх, стенд показывает модалку 380px вместо настоящих 560.
//
// Порядок барелей между собой — как в App.jsx: чат, база знаний, файлы,
// администрирование, настройки. Панель, чей барель поднят выше своего места,
// проигрывает соседям спор одинаковых специфичностей — ровно тот дефект, из-за
// которого этот блок и стоит внизу файла.
import '@/components/chatPanel/chatWindow.css';
import '@/components/knowledgeBasePanel/KnowledgeBase.css';
import '@/components/filesPanel/filesPanel.css';
import '@/components/adminPanel/adminPanel.css';
import '@/components/settingsPanel/settingsPanel.css';

const FRAMES = {
  // Колонка правой панели: ширину задаёт та же переменная, что и в приложении.
  panel: (node) => (
    <div className="workspace" style={{ height: '100vh' }}>
      <aside className="workspace__side workspace__side--right">
        <div className="workspace__side-body">{node}</div>
      </aside>
    </div>
  ),
  // Правая панель целиком, вместе со своей шапкой: вкладки рисует она сама
  // (RightPanel), и кейс про набор вкладок в `panel` не разворачивается — там
  // только тело одной из них.
  right: (node) => (
    <div className="workspace" style={{ height: '100vh' }}>
      <div className="workspace__center" />
      {node}
    </div>
  ),
  // Левая панель вместе с центром: её выпадающие списки уходят порталом поверх
  // центра, и в одинокой колонке не было бы видно ни куда список попадает, ни
  // сколько места ему осталось. Панель обрезает своё содержимое
  // (`overflow: hidden`) — то, из-за чего список и уехал в портал.
  left: (node) => (
    <div className="workspace" style={{ height: '100vh' }}>
      <aside className="workspace__side workspace__side--left">
        <div className="workspace__side-toolbar">{node}</div>
      </aside>
      <div className="workspace__center" />
    </div>
  ),
  // Центр раздела. Здесь живут шапки (.workspace__head) и содержимое под ними:
  // ширина центра решает, схлопнутся ли крошки и куда встанут кнопки шапки,
  // поэтому боковые колонки в кадре тоже нужны — иначе центр шире настоящего.
  center: (node) => (
    <div className="workspace" style={{ height: '100vh' }}>
      <aside className="workspace__side workspace__side--left" />
      <div className="workspace__center">{node}</div>
    </div>
  ),
  // Тело группы «Настроек»/«Администрирования»: отступы и ширину колонки задаёт
  // `.settings-content__body`, а не сама секция. Для компонента, который эту
  // обёртку рисует сам (группа целиком), рамка — `center`.
  settings: (node) => (
    <div className="workspace" style={{ height: '100vh' }}>
      <aside className="workspace__side workspace__side--left" />
      <div className="workspace__center">
        <div className="settings-content__body">{node}</div>
      </div>
    </div>
  ),
  // Лента чата — flex-колонка: элемент без `flex: none` схлопывается в ней до
  // рамки, и увидеть это можно только здесь.
  //
  // Ширина — не на всё окно: в приложении ленту зажимают обе боковые панели, и
  // на 1440px ей остаётся около 860. Растянув её на весь экран, стенд перестал
  // бы показывать переносы и горизонтальный скролл вывода — а это половина
  // того, ради чего карточку смотрят.
  feed: (node) => (
    <div className="message-list" style={{ height: '100vh', width: 860 }}>
      {node}
    </div>
  ),
  bare: (node) => node,
};

/**
 * Ответы сервера для кейса: `{ '<начало url>': данные }` из его записи в
 * реестре. Бэкенда у стенда нет, а половина экранов без ответа показывает
 * «Загрузка…» — фикстура для них и есть тело ответа.
 *
 * Незаявленный запрос НЕ подменяется: он уходит на статику стенда, отвечает 404
 * и попадает в консольные ошибки кейса — то есть в его отчёт. Молчаливая
 * заглушка на всё подряд прятала бы забытый маршрут за правдоподобным снимком.
 *
 * Значение-строка отдаётся телом как есть (attachmentApi читает ответ как
 * текст), остальное сериализуется в JSON.
 */
function installApiStub(routes) {
  const pending = new Set();
  if (!routes) return pending;
  const real = window.fetch.bind(window);

  window.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : input.url;
    const prefix = Object.keys(routes).find((key) => url.startsWith(key));
    if (prefix === undefined) return real(input, init);

    const data = routes[prefix];
    const body = typeof data === 'string' ? data : JSON.stringify(data);
    const answer = Promise.resolve(new Response(body, { status: 200, headers: { 'content-type': 'application/json' } }));
    pending.add(answer);
    answer.finally(() => pending.delete(answer));
    return answer;
  };
  return pending;
}

/**
 * Ждём, пока экран перестанет меняться сам: ответы заглушки приходят уже
 * следующим тиком, но состояние по ним компонент ставит ещё через рендер, и
 * снимок, сделанный сразу после первого кадра, застал бы «Загрузка…».
 *
 * Кадр за кадром, а не одна пауза на глазок: цепочка «ответ → setState →
 * дочерний запрос» бывает и в два звена (группа «Инструменты» тянет каталог
 * следом за конфигом).
 */
async function settle(pending) {
  for (let i = 0; i < 4; i += 1) {
    await Promise.all([...pending]);
    await new Promise((resolve) => requestAnimationFrame(resolve));
  }
}

const id = new URLSearchParams(location.search).get('case');
const found = id ? findCase(id) : null;

// Список кейсов — он же то, из чего scripts/visual-harness.js узнаёт их имена
// (открывает эту страницу и читает data-case-id). Второй копии реестра, которая
// разъезжалась бы с этой, ни в скрипте, ни в yaml быть не должно.
const Index = () => (
  <ul style={{ padding: '1rem 2rem', fontFamily: 'system-ui', lineHeight: 1.8 }}>
    {cases.map((c) => (
      <li key={c.id}>
        <a data-case-id={c.id} href={`?case=${encodeURIComponent(c.id)}`}>
          {c.id}
        </a>
        {c.missing && ' — фикстура не найдена'}
      </li>
    ))}
  </ul>
);

const Case = ({ entry }) => FRAMES[entry.frame](entry.render(entry.props));

async function start() {
  // Ждём словарь: без него первый кадр — интерфейс из сырых ключей, и снимок
  // стенда врал бы ровно там, где его чаще всего и смотрят — в подписях.
  await i18nReady.catch(() => {});

  const pending = installApiStub(found?.api);

  let view = <Index />;
  if (id && !found) view = <pre>Нет такого кейса: {id}</pre>;
  else if (found?.missing) view = <pre>Фикстура не найдена: {id}</pre>;
  else if (found) view = <Case entry={found} />;

  ReactDOM.createRoot(document.getElementById('root')).render(view);
  await settle(pending);

  // Что сделать перед снимком — отсюда, а не из скрипта: реестр кейсов один, и
  // второй его копии в scripts/ быть не должно.
  if (found?.steps) document.documentElement.dataset.harnessSteps = JSON.stringify(found.steps);
  // Отметка «отрисовано» — на <html>, а не по содержимому #root: модалка уходит
  // порталом в document.body, и по пустому #root снимок ждал бы её вечно.
  document.documentElement.dataset.harness = 'ready';
}

start();
