import ReactDOM from 'react-dom/client';
import '@/index.css';
// Раскладка — та же, что у приложения: ширину правой панели и отступы её тела
// задаёт она (`--ws-right-width`), и без неё «панель» растеклась бы на всё окно.
import '@/components/common/layout/workspaceLayout.css';
import { i18nReady } from '@/i18n/index';
import { cases, findCase } from './registry';
// Барели стилей — ПОСЛЕ компонентов, как в приложении: там их подтягивает
// ChatWindow, то есть позже общих `common/`. Порядок здесь не косметика.
// `.modal-shell` и `.git-commands` задают ширину одинаковой специфичностью
// (0,1,0), и кто из них выиграет, решает порядок в бандле: подняв барель
// наверх, стенд показывает модалку 380px вместо настоящих 560.
import '@/components/chatPanel/chatWindow.css';

const FRAMES = {
  // Колонка правой панели: ширину задаёт та же переменная, что и в приложении.
  panel: (node) => (
    <div className="workspace" style={{ height: '100vh' }}>
      <aside className="workspace__side workspace__side--right">
        <div className="workspace__side-body">{node}</div>
      </aside>
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

const id = new URLSearchParams(location.search).get('case');
const found = id ? findCase(id) : null;

const Index = () => (
  <ul style={{ padding: '1rem 2rem', fontFamily: 'system-ui', lineHeight: 1.8 }}>
    {cases.map((c) => (
      <li key={c.id}>
        <a href={`?case=${encodeURIComponent(c.id)}`}>{c.id}</a>
        {c.missing && ' — фикстура не найдена'}
      </li>
    ))}
  </ul>
);

const Case = ({ entry }) => FRAMES[entry.frame](entry.render(entry.props));

// Ждём словарь: без него первый кадр — интерфейс из сырых ключей, и снимок
// стенда врал бы ровно там, где его чаще всего и смотрят — в подписях.
i18nReady.finally(() => {
  let view = <Index />;
  if (id && !found) view = <pre>Нет такого кейса: {id}</pre>;
  else if (found?.missing) view = <pre>Фикстура не найдена: {id}</pre>;
  else if (found) view = <Case entry={found} />;

  ReactDOM.createRoot(document.getElementById('root')).render(view);
  // Что щёлкнуть перед снимком — отсюда, а не из скрипта: реестр кейсов один, и
  // второй его копии в scripts/ быть не должно.
  if (found?.click) document.documentElement.dataset.harnessClick = found.click;
  // Отметка «отрисовано» — на <html>, а не по содержимому #root: модалка уходит
  // порталом в document.body, и по пустому #root снимок ждал бы её вечно.
  document.documentElement.dataset.harness = 'ready';
});
