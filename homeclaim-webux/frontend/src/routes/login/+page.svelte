<section class="panel">
  <span class="badge">Authentication</span>
  <h1>Sign in</h1>
  <p class="muted">
    The SvelteKit login page is a UI shell. Token validation still happens on the backend.
  </p>

  <div class="plot-card" style="max-width: 420px;">
    <label class="muted" for="token">Bearer token</label>
    <input id="token" placeholder="Paste API token" style="width: 100%; padding: 0.75rem; border-radius: 0.75rem; border: 1px solid var(--hc-border); margin: 0.5rem 0 1rem;" />
    <button class="button" type="button">Continue</button>
  </div>
</section>
