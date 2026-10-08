import { useEffect, useMemo, useRef, useState } from 'react'
import { streamLogs } from '../api'
import { SectionHeader } from './FlagField'

const MAX_LINES = 4000

type Filter = 'all' | 'metric' | 'system' | 'warn'

function classifyLine(line: string): 'cmd' | 'system' | 'metric' | 'warn' | 'err' | 'noise' | '' {
  if (line.startsWith('$')) return 'cmd'
  if (line.startsWith('[llama-control]')) return 'system'

  if (
    /done request: GET \/(slots|metrics|health|props)/.test(line) ||
    /^srv\s+update_slots:\s+all slots are idle/.test(line)
  ) {
    return 'noise'
  }

  if (line.startsWith('srv ')) return 'system'
  if (/prompt eval|eval time|tokens per second|cached_tokens|kv_cache|cache reuse/.test(line))
    return 'metric'
  if (/warn|warning/i.test(line)) return 'warn'
  if (/error|failed|fatal|abort/i.test(line)) return 'err'
  return ''
}

/**
 * Embeddable log stream panel — filter chips + autoscroll + live SSE tail of llama-server's
 * stdout. Mount this inside any flex container; it stretches to fill available space and the
 * log pane scrolls internally.
 */
export function LogStreamPanel({ headerStep }: { headerStep?: string }) {
  const [lines, setLines] = useState<{ text: string; kind: string }[]>([])
  const [autoscroll, setAutoscroll] = useState(true)
  const [showNoise, setShowNoise] = useState(false)
  const [filter, setFilter] = useState<Filter>('all')
  const paneRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const close = streamLogs((line) => {
      setLines((prev) => {
        const next = prev.length >= MAX_LINES ? prev.slice(prev.length - MAX_LINES + 1) : prev
        return [...next, { text: line, kind: classifyLine(line) }]
      })
    })
    return close
  }, [])

  useEffect(() => {
    if (autoscroll && paneRef.current) {
      paneRef.current.scrollTop = paneRef.current.scrollHeight
    }
  }, [lines, autoscroll])

  const visibleLines = useMemo(
    () => (showNoise ? lines : lines.filter((l) => l.kind !== 'noise')),
    [lines, showNoise],
  )

  const filtered = useMemo(() => {
    if (filter === 'all') return visibleLines
    return visibleLines.filter((l) =>
      filter === 'system' ? l.kind === 'system' || l.kind === 'cmd' : l.kind === filter,
    )
  }, [visibleLines, filter])

  const actualNoiseCount = lines.filter((l) => l.kind === 'noise').length

  return (
    <section className="section stream-section">
      <SectionHeader
        step={headerStep}
        title="Logstream"
        meta={`${visibleLines.length} lines · live tail · llama-server stdout`}
      />

      <div className="action-row" style={{ marginBottom: '0.65rem' }}>
        <FilterChip
          label="all"
          active={filter === 'all'}
          onClick={() => setFilter('all')}
          count={visibleLines.length}
        />
        <FilterChip
          label="metrics"
          active={filter === 'metric'}
          onClick={() => setFilter('metric')}
          count={visibleLines.filter((l) => l.kind === 'metric').length}
        />
        <FilterChip
          label="system"
          active={filter === 'system'}
          onClick={() => setFilter('system')}
          count={visibleLines.filter((l) => l.kind === 'system' || l.kind === 'cmd').length}
        />
        <FilterChip
          label="warnings"
          active={filter === 'warn'}
          onClick={() => setFilter('warn')}
          count={visibleLines.filter((l) => l.kind === 'warn' || l.kind === 'err').length}
        />
        <span style={{ flex: 1 }} />
        <button
          className={'btn ' + (showNoise ? '' : 'ghost')}
          onClick={() => setShowNoise((v) => !v)}
          title="llama-server logs each /slots and /metrics request we make — hidden by default"
          style={{ padding: '0.4rem 0.75rem' }}
        >
          {showNoise ? '◉' : '◌'} polling traffic{' '}
          <span className="dim" style={{ marginLeft: '0.35rem' }}>
            {actualNoiseCount}
          </span>
        </button>
        <button
          className={'btn ' + (autoscroll ? '' : 'ghost')}
          onClick={() => setAutoscroll((v) => !v)}
          style={{ padding: '0.4rem 0.75rem' }}
        >
          ↧ autoscroll {autoscroll ? 'on' : 'off'}
        </button>
        <button
          className="btn ghost"
          onClick={() => setLines([])}
          style={{ padding: '0.4rem 0.75rem' }}
        >
          ⌫ clear
        </button>
      </div>

      <div className="log-pane stream-pane" ref={paneRef}>
        {filtered.length === 0 ? (
          <div className="empty-state">
            <span className="glyph">⌧</span>
            no log lines
            {lines.length > 0 ? ' match this filter' : '. start a server from the launch tab.'}
          </div>
        ) : (
          filtered.map((l, i) => (
            <div key={i} className={'log-line ' + l.kind}>
              <span className="ln">{(i + 1).toString().padStart(4, ' ')}</span>
              <span>{l.text}</span>
            </div>
          ))
        )}
      </div>
    </section>
  )
}

function FilterChip({
  label,
  active,
  onClick,
  count,
}: {
  label: string
  active: boolean
  onClick: () => void
  count: number
}) {
  return (
    <button
      className={'btn ' + (active ? '' : 'ghost')}
      onClick={onClick}
      style={{ padding: '0.4rem 0.75rem' }}
    >
      {label}
      <span className="dim" style={{ marginLeft: '0.35rem' }}>
        {count}
      </span>
    </button>
  )
}
