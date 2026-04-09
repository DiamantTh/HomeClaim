<script lang="ts">
  import { onMount } from 'svelte';
  import PlotCard from '$lib/components/PlotCard.svelte';
  import { getPlots, type PlotSummary } from '$lib/api';

  let plots: PlotSummary[] = [];

  onMount(async () => {
    const allPlots = await getPlots();
    plots = allPlots.filter((plot) => plot.isOwner);
  });
</script>

<section class="panel">
  <h1>My plots</h1>
  <p class="muted">Owner-focused view for HomeClaim management tasks.</p>

  {#if plots.length === 0}
    <p class="muted">No owned plots were returned by the API yet.</p>
  {:else}
    <div class="grid">
      {#each plots as plot}
        <PlotCard {plot} />
      {/each}
    </div>
  {/if}
</section>
