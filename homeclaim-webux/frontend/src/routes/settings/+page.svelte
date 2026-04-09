<script lang="ts">
  let notifications = true;
  let darkMode = true;
</script>

<section class="panel">
  <span class="badge">Settings</span>
  <h1>WebUX preferences</h1>
  <p class="muted">Initial SvelteKit replacement for the settings page.</p>

  <div class="grid">
    <label class="plot-card">
      <strong>Notification toasts</strong>
      <span class="muted">Receive live HomeClaim notifications via WebSocket.</span>
      <input type="checkbox" bind:checked={notifications} />
    </label>

    <label class="plot-card">
      <strong>Dark mode</strong>
      <span class="muted">Persist theme preference in the browser.</span>
      <input type="checkbox" bind:checked={darkMode} />
    </label>
  </div>
</section>
