<script lang="ts">
  import { onMount } from 'svelte';
  import { getPlot, type PlotSummary } from '$lib/api';

  export let data: { plotId: string };

  let plot: PlotSummary | null = null;

  onMount(async () => {
    plot = await getPlot(data.plotId);
  });
</script>

<section class="panel">
  {#if plot}
    <span class="badge">Plot detail</span>
    <h1>{plot.name}</h1>
    <p class="muted">{plot.description}</p>

    <div class="metrics">
      <div class="metric">
        <strong>{plot.owner}</strong>
        <span class="muted">Owner</span>
      </div>
      <div class="metric">
        <strong>{plot.world}</strong>
        <span class="muted">World</span>
      </div>
      <div class="metric">
        <strong>{plot.likes}</strong>
        <span class="muted">Likes</span>
      </div>
      <div class="metric">
        <strong>{plot.visits}</strong>
        <span class="muted">Visits</span>
      </div>
    </div>

    <div class="panel">
      <h2>Migration notes</h2>
      <p class="muted">
        This detail view is now driven by SvelteKit and is ready to replace the old
        Pebble/Alpine page once the API endpoints are finalized.
      </p>
    </div>
  {:else}
    <p class="muted">Loading plot…</p>
  {/if}
</section>
