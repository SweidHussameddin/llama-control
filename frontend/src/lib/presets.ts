import type { LlamaFlags } from '../types'

export type Preset = {
  id: string
  name: string
  description: string
  /** flags applied on top of whatever is already in the form */
  flags: Partial<LlamaFlags>
}

export const PRESETS: Preset[] = [
  {
    id: 'agent',
    name: 'Coding Agent',
    description: 'Balanced for long-context coding sessions with tool calls. Single slot, prefix caching on.',
    flags: {
      ctxSize: 32768,
      gpuLayers: 99,
      parallel: 1,
      flashAttn: true,
      cacheReuse: 256,
      jinja: true,
      metrics: true,
      mlock: true,
    },
  },
  {
    id: 'speed',
    name: 'Speed',
    description: 'Smaller context, huge physical batch. Fastest prompt processing per turn.',
    flags: {
      ctxSize: 8192,
      gpuLayers: 99,
      ubatchSize: 2048,
      batchSize: 2048,
      flashAttn: true,
      cacheReuse: 256,
      jinja: true,
      metrics: true,
    },
  },
  {
    id: 'moe-squeeze',
    name: 'MoE Squeeze',
    description: 'Big MoE on 12 GB VRAM. Experts on CPU, KV quantized, prefix caching on.',
    flags: {
      ctxSize: 16384,
      gpuLayers: 99,
      cpuMoeLayers: 30,
      flashAttn: true,
      cacheTypeK: 'q4_0',
      cacheTypeV: 'q4_0',
      kvUnified: true,
      cacheReuse: 256,
      jinja: true,
      metrics: true,
      mlock: true,
    },
  },
  {
    id: 'max-context',
    name: 'Max Context',
    description: 'Maximum context length. KV cache quantized to fit. Use for long-document tasks.',
    flags: {
      ctxSize: 131072,
      gpuLayers: 99,
      flashAttn: true,
      cacheTypeK: 'q4_0',
      cacheTypeV: 'q4_0',
      kvUnified: true,
      cacheReuse: 256,
      jinja: true,
      metrics: true,
    },
  },
]
