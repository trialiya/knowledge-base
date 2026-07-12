import '@testing-library/jest-dom';

// React 19 requires this flag for act()-wrapped state updates in tests;
// react-scripts' jsdom test environment doesn't set it on its own.
global.IS_REACT_ACT_ENVIRONMENT = true;
