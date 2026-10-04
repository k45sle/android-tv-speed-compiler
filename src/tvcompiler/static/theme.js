(() => {
  const key = 'tvcompiler.appearance';
  const choices = ['system', 'light', 'night'];
  let theme = 'system';
  try {
    const saved = window.localStorage.getItem(key);
    if (choices.includes(saved)) theme = saved;
  } catch {
    // Storage can be disabled by the browser or its privacy settings.
  }

  document.documentElement.dataset.theme = theme;

  document.addEventListener('DOMContentLoaded', () => {
    const control = document.querySelector('#appearance');
    if (!control) return;
    control.value = theme;
    control.addEventListener('change', () => {
      theme = choices.includes(control.value) ? control.value : 'system';
      document.documentElement.dataset.theme = theme;
      try {
        window.localStorage.setItem(key, theme);
      } catch {
        // The current page still changes theme when storage is unavailable.
      }
    });
  }, { once: true });
})();
