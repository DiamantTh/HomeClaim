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
      <div class="metric">
        <strong>{plot.entryDenyCount ?? plot.entryDenies?.length ?? 0}</strong>
        <span class="muted">Entry denies</span>
      </div>
    </div>

    <section class="access-section">
      <div>
        <h2>Access controls</h2>
        <p class="muted">Entry deny rules are checked for movement and teleport targets.</p>
      </div>

      {#if plot.entryDenies?.length}
        <div class="deny-list">
          {#each plot.entryDenies as rule}
            <article class="plot-card">
              <div class="meta">
                <span class="badge">{rule.status}</span>
                <span>{rule.targetType}: {rule.targetValue}</span>
              </div>
              <strong>{rule.reason}</strong>
              <p class="muted">Rule ID: {rule.id}</p>
            </article>
          {/each}
        </div>
      {:else}
        <p class="muted">No active entry deny rules are exposed for this plot.</p>
      {/if}
    </section>
  {:else}
    <p class="muted">Loading plot…</p>
  {/if}
</section>

<style>
  .access-section {
    display: grid;
    gap: 1rem;
    margin-top: 1.5rem;
  }

  .access-section h2 {
    margin: 0;
  }

  .deny-list {
    display: grid;
    gap: 0.75rem;
  }
</style>
