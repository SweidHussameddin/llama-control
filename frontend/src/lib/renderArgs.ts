import type { LlamaFlags } from '../types'

/** Turns the flag form into the argv llama-server receives; mirrors FlagsRenderer on the backend. */
export function renderArgs(flags: LlamaFlags, architecture?: string | null): string[] {
  const out: string[] = []
  const addStr = (flag: string, v?: string | null) => {
    if (v != null && v !== '') {
      out.push(flag, v)
    }
  }
  const addNum = (flag: string, v?: number | null) => {
    if (v != null && !Number.isNaN(v)) {
      out.push(flag, String(v))
    }
  }

  addStr('-m', flags.modelPath)
  addStr('--alias', flags.alias)
  addStr('--host', flags.host)
  addNum('--port', flags.port)
  addStr('--api-key', flags.apiKey)
  if (flags.metrics) out.push('--metrics')
  if (flags.jinja) out.push('--jinja')

  addNum('-c', flags.ctxSize)
  addNum('-ngl', flags.gpuLayers)
  addNum('-t', flags.threads)
  addNum('-b', flags.batchSize)
  addNum('-ub', flags.ubatchSize)
  addNum('--parallel', flags.parallel)
  addNum('-s', flags.seed)
  addNum('--keep', flags.keep)

  if (flags.cpuMoe) out.push('--cpu-moe')
  else addNum('--n-cpu-moe', flags.cpuMoeLayers)

  if (flags.kvUnified) out.push('--kv-unified')
  if (flags.noKvOffload) out.push('-nkvo')
  addStr('-ctk', flags.cacheTypeK)
  addStr('-ctv', flags.cacheTypeV)
  if (flags.flashAttn != null) out.push('-fa', flags.flashAttn ? 'on' : 'off')

  if (flags.mlock) out.push('--mlock')
  if (flags.noMmap) out.push('--no-mmap')

  addNum('--cache-reuse', flags.cacheReuse)

  if (flags.ropeFreqBase != null) out.push('--rope-freq-base', String(flags.ropeFreqBase))
  if (flags.ropeFreqScale != null) out.push('--rope-freq-scale', String(flags.ropeFreqScale))
  addStr('--rope-scaling', flags.ropeScaling)

  // Number-of-experts override-kv (architecture-specific key).
  if (flags.expertsUsed != null && architecture) {
    out.push('--override-kv', `${architecture}.expert_used_count=int:${flags.expertsUsed}`)
  }

  if (flags.extraArgs?.length) out.push(...flags.extraArgs)
  return out
}
