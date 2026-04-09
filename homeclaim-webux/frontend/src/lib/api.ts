export interface PlotSummary {
  id: string;
  name: string;
  owner: string;
  world: string;
  description: string;
  likes: number;
  visits: number;
  featured: boolean;
  isOwner: boolean;
  tags: string[];
}

const fallbackPlots: PlotSummary[] = [
  {
    id: 'spawn-garden',
    name: 'Spawn Garden',
    owner: 'DiamantTh',
    world: 'world_plots',
    description: 'Reference plot for the new HomeClaim WebUX migration.',
    likes: 12,
    visits: 128,
    featured: true,
    isOwner: true,
    tags: ['featured', 'spawn']
  },
  {
    id: 'market-square',
    name: 'Market Square',
    owner: 'Builder42',
    world: 'world_plots',
    description: 'Trading hub plot with public access settings.',
    likes: 7,
    visits: 76,
    featured: false,
    isOwner: false,
    tags: ['shop', 'public']
  },
  {
    id: 'sky-district',
    name: 'Sky District',
    owner: 'CloudCrafter',
    world: 'legacy_plots',
    description: 'Imported PlotSquared example used to verify migration flow.',
    likes: 19,
    visits: 204,
    featured: true,
    isOwner: false,
    tags: ['imported', 'plotsquared']
  }
];

async function fetchJson<T>(url: string, fallback: T): Promise<T> {
  try {
    const response = await fetch(url, {
      headers: { accept: 'application/json' }
    });

    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`);
    }

    return (await response.json()) as T;
  } catch {
    return fallback;
  }
}

export async function getPlots(): Promise<PlotSummary[]> {
  return fetchJson('/api/plots', fallbackPlots);
}

export async function getPlot(id: string): Promise<PlotSummary> {
  const fallback = fallbackPlots.find((plot) => plot.id === id) ?? {
    id,
    name: `Plot ${id}`,
    owner: 'Unknown',
    world: 'world_plots',
    description: 'No API data available yet for this plot.',
    likes: 0,
    visits: 0,
    featured: false,
    isOwner: false,
    tags: ['placeholder']
  };

  return fetchJson(`/api/plots/${id}`, fallback);
}
