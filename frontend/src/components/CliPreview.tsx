import { useState } from 'react'
import { renderArgs } from '../lib/renderArgs'
import type { LlamaFlags } from '../types'

export function CliPreview({ flags, binary = 'llama-server-cuda' }: { flags: LlamaFlags; binary?: string }) {
  const [copied, setCopied] = useState(false)
  const args = renderArgs(flags)
  const cliText = [binary, ...args].join(' ')

  const tokens: { kind: 'bin' | 'flag' | 'val'; text: string }[] = [{ kind: 'bin', text: binary }]
  for (const a of args) {
    tokens.push({ kind: a.startsWith('-') ? 'flag' : 'val', text: a })
  }

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(cliText)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // ignore
    }
  }

  return (
    <div className="cli-preview">
      <button className="btn ghost cli-preview-copy" onClick={copy}>
        {copied ? '✓ copied' : '⧉ copy'}
      </button>
      {tokens.map((t, i) => (
        <span key={i} className={t.kind}>
          {t.text}
          {i < tokens.length - 1 && ' '}
        </span>
      ))}
    </div>
  )
}
