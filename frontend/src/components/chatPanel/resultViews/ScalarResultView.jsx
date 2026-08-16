// Режим «Обзор» для формы «скаляр»: значение как есть, без кавычек, тёмного
// фона и подсветки — их стоило разворачивать ради содержимого файла, не ради
// слова «Done».

const ScalarResultView = ({ data }) => <div className="tool-scalar">{data.value}</div>;

export default ScalarResultView;
