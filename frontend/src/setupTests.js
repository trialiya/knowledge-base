import '@testing-library/jest-dom';

// jsdom не реализует scrollIntoView, а он есть у любой навигации по списку
// (useListNavigation, useSearchDropdown): без заглушки тест падает не по делу.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}
