<script lang="ts">
  import type { PlotSummary } from '$lib/api';

  export let plot: PlotSummary;
</script>

<article class="plot-card">
  {#if plot.featured}
    <span class="badge">★ Featured</span>
  {/if}

  <div>
    <h3>{plot.name}</h3>
    <p class="muted">{plot.description}</p>
  </div>

  <div class="meta">
    <span>Owner: {plot.owner}</span>
    <span>World: {plot.world}</span>
    <span>{plot.likes} likes</span>
    <span>{plot.visits} visits</span>
  </div>

  <div class="meta">
    {#each plot.tags as tag}
      <span class="badge">#{tag}</span>
    {/each}
  </div>

  <div class="actions">
    <a class="button" href={`/app/plot/${plot.id}`}>Open plot</a>
    {#if plot.isOwner}
      <a class="button secondary" href="/app/my-plots">Manage</a>
    {/if}
  </div>
</article>
