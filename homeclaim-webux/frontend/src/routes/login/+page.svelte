<script lang="ts">
  let code = '';
  let error = '';
  let busy = false;

  async function pair() {
    error = '';
    busy = true;
    try {
      const response = await fetch('/auth/pair', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: code.trim() })
      });
      if (!response.ok) throw new Error('Code ungültig oder abgelaufen. Erzeuge im Spiel einen neuen Code.');
      window.location.assign('/app/');
    } catch (cause) {
      error = cause instanceof Error ? cause.message : 'Anmeldung fehlgeschlagen.';
    } finally {
      busy = false;
    }
  }
</script>

<section class="panel">
  <span class="badge">Minecraft-Konto</span>
  <h1>Anmelden</h1>
  <p class="muted">Führe im Spiel <code>/homeclaim web login</code> aus. Der Code ist zwei Minuten gültig und kann nur einmal verwendet werden.</p>

  <form class="plot-card" style="max-width: 420px;" on:submit|preventDefault={pair}>
    <label class="muted" for="pair-code">Einmaliger Ingame-Code</label>
    <input id="pair-code" bind:value={code} autocomplete="one-time-code" required maxlength="64" placeholder="Code aus dem Spielchat" style="width: 100%; padding: 0.75rem; border-radius: 0.75rem; border: 1px solid var(--hc-border); margin: 0.5rem 0 1rem;" />
    {#if error}<p role="alert" class="error">{error}</p>{/if}
    <button class="button" type="submit" disabled={busy}>{busy ? 'Anmeldung…' : 'Anmelden'}</button>
  </form>
</section>
