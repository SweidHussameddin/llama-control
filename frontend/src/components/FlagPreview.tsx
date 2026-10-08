import type { LlamaFlags } from '../types'
import { renderArgs } from '../lib/renderArgs'

export function FlagPreview({ flags, binary = 'llama-server' }: { flags: LlamaFlags; binary?: string }) {
  const args = renderArgs(flags)
  const cli = [binary, ...args].join(' ')
  return <div className="flag-preview">{cli}</div>
}
