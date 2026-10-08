import type { MetricsSnapshot } from '../types'
import { SectionHeader } from '../components/FlagField'

function fmtInt(n: number | null | undefined): string {
  if (n == null) return '—'
  return n.toLocaleString()
}

export function SlotsPage({ snapshot }: { snapshot: MetricsSnapshot | null }) {
  if (!snapshot) {
    return (
      <div className="empty-state">
        <span className="glyph">⌬</span>
        connecting…
      </div>
    )
  }
  if (!snapshot.serverReachable) {
    return (
      <div className="banner info">
        <span className="glyph">i</span>
        <span>llama-server isn’t reachable. Start it from the launch tab.</span>
      </div>
    )
  }
  if (snapshot.slots.length === 0) {
    return (
      <div className="empty-state">
        <span className="glyph">⌧</span>
        no slot data yet. send a request to llama-server to populate slots.
      </div>
    )
  }

  const activeCount = snapshot.slots.filter((s) => s.isProcessing).length

  return (
    <div className="stack-lg">
      <SectionHeader
        title="Slots"
        meta={
          <>
            {snapshot.slots.length} total · <span className="live">{activeCount} active</span>
          </>
        }
      />

      <div className="banner info">
        <span className="glyph">i</span>
        <span>
          Each slot holds its own KV cache. <strong>Past tokens</strong> is what’s currently resident
          in that slot’s cache — when it stays non-zero between requests, prefix caching is working.
          With <code>--parallel 1</code>, requests cycle through one slot and prefix reuse is most predictable.
        </span>
      </div>

      <div className="slots-grid">
        {snapshot.slots.map((slot) => (
          <div key={slot.id} className={'slot-card' + (slot.isProcessing ? ' active' : '')}>
            <div className="hdr">
              <span className="id">slot · {slot.id.toString().padStart(2, '0')}</span>
              <span className={'pill ' + (slot.isProcessing ? 'live' : 'idle')}>
                <span className="dot" />
                {slot.isProcessing ? 'processing' : 'idle'}
              </span>
            </div>
            <div className="past">
              {fmtInt(slot.pastTokens)}
              <span className="lbl">past tokens (kv)</span>
            </div>
            <div className="meta">
              <span className="k">prompt</span>
              <span className="v">{fmtInt(slot.promptTokens)}</span>
              <span className="k">predicted</span>
              <span className="v">{fmtInt(slot.predictedTokens)}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
