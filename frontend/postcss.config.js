// CRA доклеивал вендорные префиксы через postcss-preset-env; Vite сам этого не
// делает, поэтому autoprefixer подключён явно — иначе из сборки пропадают
// -webkit-fit-content / -webkit-max-content. Таргеты берутся из browserslist
// в package.json.
import autoprefixer from 'autoprefixer';

export default {
  plugins: [autoprefixer()],
};
