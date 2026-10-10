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
      const allPlots = await getPlots();
      plots = allPlots.filter((plot) => plot.isOwner);
    } catch (cause) {
      error = cause instanceof Error ? cause.message : 'Could not load your plots.';
      unauthorized = cause instanceof ApiRequestError && (cause.status === 401 || cause.status === 403);
    } finally {
      loading = false;
    }
  });
</script>

<section class="panel">
  <h1>My plots</h1>
  <p class="muted">Owner-focused view for HomeClaim management tasks.</p>

  {#if loading}
    <p class="muted">Loading your plots…</p>
  {:else if error}
    <p role="alert">{unauthorized ? 'You are not authorized to view your plots.' : `Plot service error: ${error}`}</p>
  {:else if plots.length === 0}
    <p class="muted">No owned plots were returned by the API yet.</p>
  {:else}
    <div class="grid">
      {#each plots as plot}
        <PlotCard {plot} />
      {/each}
    </div>
  {/if}
</section>
