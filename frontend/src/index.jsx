import { StrictMode } from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './App';
import { i18nReady } from './i18n';

const root = ReactDOM.createRoot(document.getElementById('root'));

// Рендерим после загрузки словаря: словари приезжают отдельным чанком, и первый
// кадр без них был бы интерфейсом из сырых ключей. Ошибку загрузки не глотаем в
// пустой экран — приложение поднимается, i18next отдаёт ключи как есть.
i18nReady.finally(() =>
  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  ),
);
