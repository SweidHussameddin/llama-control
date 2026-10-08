import type {
  MetricsSnapshot,
  ModelDetails,
  ModelFile,
  Profile,
  ServerStatus,
} from './types'

async function jsonReq<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const r = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!r.ok) throw new Error(`${r.status} ${r.statusText}`)
  if (r.status === 204) return undefined as T
  return (await r.json()) as T
}

export const api = {
  health: () => jsonReq<{ status: string; service: string }>('/api/health'),

  listModels: () => jsonReq<ModelFile[]>('/api/models'),
  modelDetails: (path: string) =>
    jsonReq<ModelDetails>(`/api/models/details?path=${encodeURIComponent(path)}`),

  listProfiles: () => jsonReq<Profile[]>('/api/profiles'),
  saveProfile: (p: Profile) =>
    jsonReq<Profile>('/api/profiles', {
      method: 'POST',
      body: JSON.stringify(p),
    }),
  deleteProfile: (id: string) =>
    jsonReq<void>(`/api/profiles/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    }),

  serverStatus: () => jsonReq<ServerStatus>('/api/server/status'),
  startServerInline: (p: Profile) =>
    jsonReq<ServerStatus>('/api/server/start', {
      method: 'POST',
      body: JSON.stringify(p),
    }),
  startServerById: (id: string) =>
    jsonReq<ServerStatus>(`/api/server/start/${encodeURIComponent(id)}`, {
      method: 'POST',
    }),
  stopServer: () =>
    jsonReq<ServerStatus>('/api/server/stop', { method: 'POST' }),

  metricsSnapshot: () => jsonReq<MetricsSnapshot>('/api/metrics'),
}

export function streamMetrics(onMessage: (s: MetricsSnapshot) => void): () => void {
  const es = new EventSource('/api/metrics/stream')
  es.onmessage = (e) => {
    try {
      onMessage(JSON.parse(e.data) as MetricsSnapshot)
    } catch {
      // ignore parse errors
    }
  }
  return () => es.close()
}

export function streamLogs(onLine: (line: string) => void): () => void {
  const es = new EventSource('/api/logs/stream')
  es.onmessage = (e) => onLine(e.data)
  return () => es.close()
}
