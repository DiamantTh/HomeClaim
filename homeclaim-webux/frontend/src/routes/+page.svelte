<script lang="ts">
  import { onMount } from 'svelte';
  import PlotCard from '$lib/components/PlotCard.svelte';
  import { ApiRequestError, getPlots, type PlotSummary } from '$lib/api';

  let plots: PlotSummary[] = [];
  let loading = true;
  let error: string | null = null;
  let unauthorized = false;

  onMount(async () => {
    try {
      plots = (await getPlots()).slice(0, 2);
    } catch (cause) {
      error = cause instanceof Error ? cause.message : 'Could not load plots.';
      unauthorized = cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403);
    } finally {
      loading = false;
    }
  });
</script>

<section class="hero">
  <div>
    <h1>Modern HomeClaim frontend</h1>
    <p class="muted">
      This SvelteKit app is now the primary frontend for WebUX development. It keeps the
      live WebSocket connection and consumes the same `/api/plots` endpoints as the legacy UI.
    </p>
  </div>

  <div class="metrics">
    <div class="metric">
      <strong>SvelteKit</strong>
      <span class="muted">Primary UI stack</span>
    </div>
    <div class="metric">
      <strong>PlotSquared</strong>
      <span class="muted">Import bridge verified</span>
    </div>
    <div class="metric">
      <strong>HomeClaim</strong>
      <span class="muted">Custom plot generator</span>
    </div>
  </div>
</section>

<section class="panel">
  <h2>Recent plots</h2>
  <p class="muted">A small live preview of plots from the HomeClaim API.</p>

  {#if loading}
    <p class="muted">Loading plots…</p>
  {:else if error}
    <p role="alert">{unauthorized ? 'You are not authorized to view recent plots.' : `Plot service error: ${error}`}</p>
  {:else if plots.length === 0}
    <p class="muted">No plots were returned by the API.</p>
  {:else}
    <div class="grid">
      {#each plots as plot}
        <PlotCard {plot} />
      {/each}
    </div>
  {/if}
</section>
