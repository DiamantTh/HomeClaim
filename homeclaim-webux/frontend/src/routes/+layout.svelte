<script lang="ts">
  import '../app.css';
  import { base } from '$app/paths';
  import { page } from '$app/stores';
  import { initWebSocket, wsConnected } from '$lib/stores/ws';
  import { onMount } from 'svelte';

  let darkMode = false;
  let username = '';
  let csrfToken = '';

  onMount(async () => {
    darkMode = window.localStorage.getItem('homeclaim-theme') === 'dark';
    document.documentElement.dataset.theme = darkMode ? 'dark' : 'light';
    const response = await fetch('/auth/session', { cache: 'no-store' });
    if (response.ok) {
      const session = await response.json();
      username = session.username;
      csrfToken = session.csrfToken;
      initWebSocket();
    }
  });

  async function logout() {
    await fetch('/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': csrfToken } });
    window.location.assign('/app/login');
  }

  function toggleTheme() {
    darkMode = !darkMode;
    document.documentElement.dataset.theme = darkMode ? 'dark' : 'light';
    window.localStorage.setItem('homeclaim-theme', darkMode ? 'dark' : 'light');
  }

  function appHref(path = '') {
    return `${base}${path}`;
  }

  function isNavActive(path = '') {
    const pathname = `${$page.url.pathname}`;
    const target = appHref(path);

    return path
      ? pathname === target || pathname.startsWith(`${target}/`)
      : pathname === target || pathname === `${target}/` || pathname === '/';
  }
</script>

<svelte:head>
  <title>HomeClaim WebUX</title>
</svelte:head>

<div class="shell">
  <header class="topbar">
    <div class="brand">
      <div class="brand-badge">HC</div>
      <div>
        <div>HomeClaim</div>
        <div class="muted">SvelteKit WebUX</div>
      </div>
    </div>

    <nav class="nav-links" aria-label="Primary navigation">
      <a href={appHref('')} class:active={isNavActive('')}>Dashboard</a>
      <a href={appHref('/plots')} class:active={isNavActive('/plots')}>Plots</a>
      <a href={appHref('/my-plots')} class:active={isNavActive('/my-plots')}>My plots</a>
    </nav>

    <div class="actions">
      <span class="muted">{username}</span>
      <span class:offline={!$wsConnected} class="status-pill">
        {$wsConnected ? 'Connected' : 'Offline'}
      </span>
      <button class="button secondary" type="button" on:click={toggleTheme}>
        {darkMode ? 'Light mode' : 'Dark mode'}
      </button>
      <button class="button secondary" type="button" on:click={logout}>Sign out</button>
    </div>
  </header>

  <main class="main">
    <slot />
  </main>

  <footer class="footer">
    HomeClaim WebUX · SvelteKit migration bridge
  </footer>
</div>
