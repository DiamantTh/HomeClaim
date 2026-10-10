export interface EntryDenyRule {
  id: string;
  regionId: string;
  targetType: string;
  targetValue: string;
  reason: string;
  createdBy: string;
  createdAt: string;
  expiresAt?: string | null;
  status: string;
  reportedBy?: string | null;
  reportedAt?: string | null;
  reportReason?: string | null;
  revokedBy?: string | null;
  revokedAt?: string | null;
  revokeReason?: string | null;
}

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
  entryDenies?: EntryDenyRule[];
  entryDenyCount?: number;
}

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number | null
  ) {
    super(message);
    this.name = 'ApiRequestError';
  }
}

async function fetchJson<T>(url: string): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, { headers: { accept: 'application/json' } });
  } catch (error) {
    throw new ApiRequestError(error instanceof Error ? error.message : 'The API could not be reached.', null);
  }

  if (!response.ok) {
    throw new ApiRequestError(`Request failed: ${response.status}`, response.status);
  }

  return (await response.json()) as T;
}

export async function getPlots(): Promise<PlotSummary[]> {
  return fetchJson('/api/plots');
}

export async function getPlot(id: string): Promise<PlotSummary> {
  return fetchJson(`/api/plots/${encodeURIComponent(id)}`);
}
