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
      plots = await getPlots();
    } catch (cause) {
      error = cause instanceof Error ? cause.message : 'Could not load plots.';
      unauthorized = cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403);
    } finally {
      loading = false;
    }
  });
</script>

<section class="panel">
  <h1>All plots</h1>
  <p class="muted">The SvelteKit plot browser consumes the same API as the old Alpine.js view.</p>

  {#if loading}
    <p class="muted">Loading plots…</p>
  {:else if error}
    <p role="alert">{unauthorized ? 'You are not authorized to view these plots.' : `Plot service error: ${error}`}</p>
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
