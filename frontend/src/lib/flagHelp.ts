import type { LlamaFlags } from '../types'

export type FlagHelp = {
  /** what the flag does — one short sentence */
  what: string
  /** when to change it from default */
  when: string
  /** default value (or "—" for "no default") */
  default?: string
  /** if true, this flag is the kind LM Studio doesn't surface */
  special?: boolean
}

export const FLAG_HELP: Partial<Record<keyof LlamaFlags, FlagHelp>> = {
  modelPath: {
    what: 'The .gguf file to load and serve.',
    when: 'Required.',
  },
  alias: {
    what: 'Name OpenAI-compatible clients send to identify the model.',
    when: "Set if your client errors with 'model not found'. Most clients accept any name.",
  },
  host: {
    what: 'Network interface llama-server binds to.',
    when: '127.0.0.1 for localhost only, 0.0.0.0 for LAN access.',
    default: '127.0.0.1',
  },
  port: {
    what: 'TCP port llama-server listens on.',
    when: 'Change if 8080 is taken.',
    default: '8080',
  },
  apiKey: {
    what: 'Bearer token required on every client request.',
    when: 'Only needed if exposing the server on a network. Leave blank for localhost.',
  },
  metrics: {
    what: 'Exposes Prometheus metrics at /metrics.',
    when: "Required for this dashboard's pp/tg charts and cache-hit visibility.",
    default: 'off',
    special: true,
  },
  jinja: {
    what: "Use the model's built-in chat template for conversations.",
    when: 'Required for tool calling, reasoning models (Qwen3, Llama 4), and any modern chat workflow. Leave on.',
    default: 'off',
    special: true,
  },
  ctxSize: {
    what: 'Maximum context length in tokens. KV cache size scales with this.',
    when: 'Coding agents rarely need >32k. Drop this first if you OOM.',
    default: 'from model',
  },
  gpuLayers: {
    what: 'Number of transformer layers stored in VRAM.',
    when: '99 = all layers. Lower if model doesn’t fit, but for MoE models prefer --n-cpu-moe instead.',
    default: '0',
  },
  threads: {
    what: 'CPU threads used for any work running on CPU (layers, experts, prompt processing).',
    when: 'Set to your physical core count (not logical/SMT). Auto-detect if blank.',
    default: 'auto',
  },
  batchSize: {
    what: 'Logical batch — max tokens consumed per prompt-processing step.',
    when: 'Default is fine for most cases. Bigger only helps if individual prompts exceed it.',
    default: '2048',
  },
  ubatchSize: {
    what: 'Physical batch — tokens processed per GPU kernel launch.',
    when: 'Bumping from 512 → 2048 is the single biggest lever for prompt-processing speed. Costs VRAM during pp.',
    default: '512',
    special: true,
  },
  parallel: {
    what: 'Number of concurrent request slots. Each slot holds its own KV cache.',
    when: 'Set to 1 for a single coding agent. Increase only for parallel chats or batch jobs.',
    default: '1',
  },
  seed: {
    what: 'RNG seed for sampling.',
    when: 'Set to a fixed integer for reproducible outputs. Leave blank for random.',
    default: '-1',
  },
  keep: {
    what: 'Always keep the first N tokens of every prompt in cache — never evict.',
    when: 'Set to your system-prompt length so it’s never reprocessed.',
    default: '0',
    special: true,
  },
  cpuMoeLayers: {
    what: 'Keep the MoE expert weights of the first N layers on CPU (not GPU).',
    when: 'For MoE models that don’t fit fully in VRAM. Start high (e.g. 30 for Qwen3.6 35B on 12 GB), lower if VRAM has headroom.',
    default: '0',
  },
  cpuMoe: {
    what: 'Push ALL MoE experts to CPU. Maximum VRAM savings, slowest.',
    when: 'Tightest VRAM scenarios only. Otherwise prefer the layer-count variant above.',
    default: 'off',
  },
  expertsUsed: {
    what: 'Number of MoE experts activated per token. Overrides the model default via --override-kv.',
    when: 'MoE models only. Lowering it speeds up inference but reduces quality; raising past the trained default rarely helps.',
    default: 'from model',
  },
  kvUnified: {
    what: 'One shared KV buffer across all request slots instead of per-slot.',
    when: 'Saves memory when --parallel > 1. Free win otherwise.',
    default: 'off',
  },
  noKvOffload: {
    what: 'Disable storing KV cache on GPU — keep it in RAM instead.',
    when: 'Almost never useful — cripples performance. Reserved for niche debugging.',
    default: 'off',
  },
  cacheTypeK: {
    what: 'K (key) cache datatype. f16 = full quality, q4_0 = 4× smaller with slight quality cost.',
    when: 'Quantize when VRAM is tight. q8_0 is a safe middle ground.',
    default: 'f16',
  },
  cacheTypeV: {
    what: 'V (value) cache datatype. Quantizing V requires Flash Attention.',
    when: 'Quantize when VRAM is tight. Match K or go lighter on V.',
    default: 'f16',
  },
  flashAttn: {
    what: 'Faster, more memory-efficient attention computation.',
    when: 'Always on unless the model crashes with it. Required for V cache quantization.',
    default: 'auto',
  },
  mlock: {
    what: 'Lock model weights in RAM so the OS can’t swap them out.',
    when: 'Recommended on Linux for stability. Skip if RAM is tight.',
    default: 'off',
  },
  noMmap: {
    what: 'Disable memory-mapped file loading; load the whole model upfront.',
    when: 'Only if mmap is causing problems. mmap is usually faster.',
    default: 'off',
  },
  cacheReuse: {
    what: 'Minimum chunk size for KV-shift-based prefix cache reuse across requests.',
    when: 'The core flag that makes coding agents fast. 256 is a good default. Higher = stricter matching but bigger reuse when it hits.',
    default: '0 (disabled)',
    special: true,
  },
  ropeFreqBase: {
    what: 'RoPE base frequency. Overrides the model’s default.',
    when: 'Only touch when extending context beyond the model’s trained length.',
    default: 'from model',
  },
  ropeFreqScale: {
    what: 'RoPE frequency scaling factor (NTK-aware).',
    when: 'Only for context extension. Leave blank unless you know your model’s recipe.',
    default: '1.0',
  },
  ropeScaling: {
    what: 'RoPE scaling method.',
    when: 'Match your model’s training: linear for older Llama-2-era, yarn for newer extended models.',
    default: 'linear',
  },
  extraArgs: {
    what: 'Any flags this UI doesn’t expose, appended verbatim to argv.',
    when: 'Use for niche flags (--override-kv, --no-warmup, --split-mode, etc.). Space-separated.',
  },
}
