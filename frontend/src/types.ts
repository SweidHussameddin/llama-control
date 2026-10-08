export type ModelFile = {
  name: string
  path: string
  sizeBytes: number
}

export type ModelDetails = {
  path: string
  architecture: string | null
  contextLength: number | null
  blockCount: number | null
  headCount: number | null
  expertCount: number | null
  expertUsedCount: number | null
}

export type LlamaFlags = {
  modelPath?: string | null
  alias?: string | null
  host?: string | null
  port?: number | null
  apiKey?: string | null
  metrics?: boolean | null
  jinja?: boolean | null
  ctxSize?: number | null
  gpuLayers?: number | null
  threads?: number | null
  batchSize?: number | null
  ubatchSize?: number | null
  parallel?: number | null
  seed?: number | null
  keep?: number | null
  cpuMoeLayers?: number | null
  cpuMoe?: boolean | null
  expertsUsed?: number | null
  kvUnified?: boolean | null
  noKvOffload?: boolean | null
  cacheTypeK?: string | null
  cacheTypeV?: string | null
  flashAttn?: boolean | null
  mlock?: boolean | null
  noMmap?: boolean | null
  cacheReuse?: number | null
  ropeFreqBase?: number | null
  ropeFreqScale?: number | null
  ropeScaling?: string | null
  extraArgs?: string[] | null
}

export type Profile = {
  id: string
  name: string
  flags: LlamaFlags
}

export type ServerStatus = {
  running: boolean
  pid: number
  profileId: string | null
  startedFlags: LlamaFlags | null
  command: string[]
  startedAtEpochMs: number | null
  exitCode: number | null
  baseUrl: string | null
}

export type SlotInfo = {
  id: number
  isProcessing: boolean
  pastTokens: number | null
  contextSize: number | null
  promptTokens: number | null
  predictedTokens: number | null
}

export type GpuInfo = {
  name: string
  utilizationPercent: number | null
  memoryUsedMb: number | null
  memoryTotalMb: number | null
  temperatureC: number | null
  powerW: number | null
}

export type CpuInfo = {
  utilizationPercent: number | null
  coreCount: number | null
  loadAverage1m: number | null
}

export type RamInfo = {
  usedMb: number | null
  totalMb: number | null
}

export type LiveInference = {
  activeRequests: number
  requestsInWindow: number
  inFlightPredictedPerSecond: number | null
  lastPromptPerSecond: number | null
  lastPredictedPerSecond: number | null
  lastPromptTokens: number | null
  lastCachedTokens: number | null
  lastCompletionTokens: number | null
  lastCacheHitRatio: number | null
  avgCacheHitRatio: number | null
  totalTokensEvaluatedInWindow: number | null
  totalGeneratedInWindow: number | null
  lastObservedAgeMs: number | null
}

export type MetricsSnapshot = {
  timestampMs: number
  serverReachable: boolean
  promptTokensPerSecond: number | null
  predictedTokensPerSecond: number | null
  kvCacheUsageRatio: number | null
  kvCacheTokens: number | null
  peakPromptTokens: number | null
  metricsAgeMs: number | null
  promptTokensTotal: number | null
  predictedTokensTotal: number | null
  requestsProcessing: number | null
  requestsDeferred: number | null
  slots: SlotInfo[]
  gpu: GpuInfo | null
  cpu: CpuInfo | null
  ram: RamInfo | null
  live: LiveInference | null
}

export const DEFAULT_FLAGS: LlamaFlags = {
  host: '127.0.0.1',
  port: 8080,
  metrics: true,
  jinja: true,
  ctxSize: 16384,
  gpuLayers: 99,
  parallel: 1,
  flashAttn: true,
  cacheTypeK: 'f16',
  cacheTypeV: 'f16',
  mlock: true,
  cacheReuse: 256,
}
