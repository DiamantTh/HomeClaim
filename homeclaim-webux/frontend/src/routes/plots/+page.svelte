<script lang="ts">
  import { onMount } from 'svelte';
  import PlotCard from '$lib/components/PlotCard.svelte';
  import { getPlots, type PlotSummary } from '$lib/api';

  let plots: PlotSummary[] = [];
  let loading = true;

  onMount(async () => {
    plots = await getPlots();
    loading = false;
  });
</script>

<section class="panel">
  <h1>All plots</h1>
  <p class="muted">The SvelteKit plot browser consumes the same API as the old Alpine.js view.</p>

  {#if loading}
    <p class="muted">Loading plots…</p>
  {:else}
    <div class="grid">
      {#each plots as plot}
        <PlotCard {plot} />
      {/each}
    </div>
  {/if}
</section>
