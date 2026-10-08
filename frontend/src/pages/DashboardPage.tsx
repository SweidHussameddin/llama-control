import { useMemo } from 'react'
import type { MetricsSnapshot } from '../types'
import { Sparkline } from '../components/Sparkline'
import { SectionHeader } from '../components/FlagField'
import { LogStreamPanel } from '../components/LogStreamPanel'

type Props = {
  snapshot: MetricsSnapshot | null
  history: MetricsSnapshot[]
}

const fmt1 = (n: number | null | undefined) =>
  n == null || !Number.isFinite(n) ? '—' : n.toFixed(1)
const fmt0 = (n: number | null | undefined) =>
  n == null || !Number.isFinite(n) ? '—' : Math.round(n).toString()
const fmtInt = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString())

/**
 * Compute an instantaneous rate from the delta of a cumulative counter across history samples.
 * Returns tokens per second.
 *
 * - Uses the last sample in the given window (or last 2 samples if window is short).
 * - Guards against negative deltas (counter reset on server restart).
 * - Returns null when there isn't enough history.
 */
/** Map each sample's live rate to an array suitable for the sparkline. */
function liveRateSeries(
  history: MetricsSnapshot[],
  picker: (s: MetricsSnapshot) => number | null | undefined,
): (number | null)[] {
  const out: (number | null)[] = []
  for (let i = 0; i < history.length; i++) {
    if (i === 0) {
      out.push(null)
      continue
    }
    const cur = picker(history[i])
    const prev = picker(history[i - 1])
    if (cur == null || prev == null || cur < prev) {
      out.push(null)
      continue
    }
    const dt = (history[i].timestampMs - history[i - 1].timestampMs) / 1000
    out.push(dt > 0 ? (cur - prev) / dt : null)
  }
  return out
}

export function DashboardPage({ snapshot, history }: Props) {
  const s = snapshot

  // === Sparkline history of inference rates ===
  // Sparklines are still derived from counter deltas (historical record over the last 90s
  // of polled data) since we don't keep a per-second history of observed live metrics.
  const ppSeries = useMemo(() => liveRateSeries(history, (h) => h.promptTokensTotal), [history])
  const tgSeries = useMemo(
    () => liveRateSeries(history, (h) => h.predictedTokensTotal),
    [history],
  )

  const gpuHist = history.map((h) => h.gpu?.utilizationPercent ?? null)
  const vramHist = history.map((h) =>
    h.gpu?.memoryUsedMb != null && h.gpu?.memoryTotalMb
      ? (h.gpu.memoryUsedMb / h.gpu.memoryTotalMb) * 100
      : null,
  )
  const tempHist = history.map((h) => h.gpu?.temperatureC ?? null)
  const powerHist = history.map((h) => h.gpu?.powerW ?? null)
  const cpuHist = history.map((h) => h.cpu?.utilizationPercent ?? null)
  const ramHist = history.map((h) =>
    h.ram?.usedMb != null && h.ram?.totalMb ? (h.ram.usedMb / h.ram.totalMb) * 100 : null,
  )

  const vramPct =
    s?.gpu?.memoryUsedMb != null && s?.gpu?.memoryTotalMb
      ? (s.gpu.memoryUsedMb / s.gpu.memoryTotalMb) * 100
      : null
  const ramPct =
    s?.ram?.usedMb != null && s?.ram?.totalMb ? (s.ram.usedMb / s.ram.totalMb) * 100 : null

  if (!s) {
    return (
      <div className="empty-state">
        <span className="glyph">⌬</span>
        connecting to metrics stream…
      </div>
    )
  }

  return (
    <div className="telemetry-page-body">
      {!s.serverReachable && (
        <div className="banner info">
          <span className="glyph">i</span>
          <span>
            llama-server isn't responding to /metrics. GPU instrumentation continues to update.
            Start a server from <strong>launch</strong> to see throughput.
          </span>
        </div>
      )}

      {/* ===== Inference (live, observed from streams flowing through our proxy) ===== */}
      <section className="section">
        <SectionHeader
          step="A"
          title="Inference"
          meta={
            s.live && s.live.activeRequests > 0 ? (
              <span className="live">⬢ {s.live.activeRequests} active · observed live</span>
            ) : s.live && s.live.lastObservedAgeMs != null ? (
              <span className="mono dim">
                last request {(s.live.lastObservedAgeMs / 1000).toFixed(0)} s ago · observed
              </span>
            ) : (
              <span className="mono dim">awaiting traffic — point your client at the proxy URL</span>
            )
          }
        />
        <div className="metric-strip">
          <div className="metric-cell accent">
            <div className="top">
              <span className="label">Prompt eval</span>
              <span className="mono dim">tok/s</span>
            </div>
            <div>
              <span className="value">{fmt0(s.live?.lastPromptPerSecond)}</span>
            </div>
            <div className="delta">
              last request · {fmtInt(s.live?.lastPromptTokens)} tok in
            </div>
            <div className="sparkline-host">
              <Sparkline data={ppSeries} color="#ffc857" fill="rgba(255,200,87,0.18)" height={50} />
            </div>
          </div>
          <div className="metric-cell live">
            <div className="top">
              <span className="label">Generation</span>
              <span className="mono dim">
                {s.live?.inFlightPredictedPerSecond != null ? '⬢ live tok/s' : 'tok/s'}
              </span>
            </div>
            <div>
              <span className="value">
                {fmt1(
                  s.live?.inFlightPredictedPerSecond != null
                    ? s.live.inFlightPredictedPerSecond
                    : s.live?.lastPredictedPerSecond,
                )}
              </span>
            </div>
            <div className="delta">
              {s.live?.inFlightPredictedPerSecond != null
                ? 'in-flight · ' + fmtInt(s.live?.activeRequests) + ' active'
                : 'last request · ' + fmtInt(s.live?.lastCompletionTokens) + ' tok out'}
            </div>
            <div className="sparkline-host">
              <Sparkline data={tgSeries} color="#5dffb0" fill="rgba(93,255,176,0.18)" height={50} />
            </div>
          </div>
          <div className="metric-cell">
            <div className="top">
              <span className="label">Cache hit</span>
              <span className="mono dim">last · avg</span>
            </div>
            <div>
              <span className="value">
                {s.live?.lastCacheHitRatio != null
                  ? `${(s.live.lastCacheHitRatio * 100).toFixed(0)}%`
                  : '—'}
              </span>
            </div>
            <div className="delta">
              {fmtInt(s.live?.lastCachedTokens)} of {fmtInt(s.live?.lastPromptTokens)} cached
              {s.live?.avgCacheHitRatio != null && (
                <>
                  {' '}
                  · avg {(s.live.avgCacheHitRatio * 100).toFixed(0)}%
                </>
              )}
            </div>
            <div
              className="sparkline-host"
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontFamily: 'var(--mono)',
                fontSize: '0.7rem',
                color: 'var(--dim)',
              }}
            >
              {s.live && s.live.requestsInWindow > 0
                ? `${s.live.requestsInWindow} reqs in last 60 s`
                : 'no recent traffic'}
            </div>
          </div>
          <div className="metric-cell">
            <div className="top">
              <span className="label">Load</span>
              <span className="mono dim">requests</span>
            </div>
            <div>
              <span className="value">
                {s.live ? s.live.activeRequests : '—'}
                <span className="unit">in flight</span>
              </span>
            </div>
            <div className="delta">
              processed: {fmtInt(s.live?.totalGeneratedInWindow)} tok gen ·{' '}
              {fmtInt(s.live?.totalTokensEvaluatedInWindow)} tok eval (window)
            </div>
            <div
              className="sparkline-host"
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: s.live?.activeRequests ? 'var(--live)' : 'var(--dim)',
                fontFamily: 'var(--mono)',
                fontSize: '0.7rem',
              }}
            >
              {s.live?.activeRequests ? '● streaming' : 'idle'}
            </div>
          </div>
        </div>
      </section>

      {/* ===== Hardware instrument strip ===== */}
      <section className="section">
        <SectionHeader
          step="B"
          title="Hardware instrumentation"
          meta={
            <>
              nvidia-smi · /proc/{`{stat,meminfo}`} · 1 Hz · last 90 s
            </>
          }
        />
        <div className="instr-strip wide">
          <div className="header">
            <div className="name">
              {s.gpu?.name?.replace('NVIDIA GeForce ', '').replace(' Laptop GPU', '') ?? 'no gpu'}
            </div>
            <div className="id">
              {s.cpu?.coreCount != null ? `host · ${s.cpu.coreCount} cores` : 'host'}
            </div>
          </div>
          <div className="gauges">
            <Gauge
              label="gpu"
              value={s.gpu?.utilizationPercent != null ? `${s.gpu.utilizationPercent}` : '—'}
              unit="%"
              bar={s.gpu?.utilizationPercent ?? null}
              history={gpuHist}
              color="#ffc857"
              sub="util"
            />
            <Gauge
              label="vram"
              value={s.gpu?.memoryUsedMb != null ? `${(s.gpu.memoryUsedMb / 1024).toFixed(1)}` : '—'}
              unit="gb"
              bar={vramPct}
              history={vramHist}
              color="#ff5e7a"
              sub={s.gpu?.memoryTotalMb != null ? `of ${(s.gpu.memoryTotalMb / 1024).toFixed(1)}` : ''}
            />
            <Gauge
              label="cpu"
              value={s.cpu?.utilizationPercent != null ? `${s.cpu.utilizationPercent}` : '—'}
              unit="%"
              bar={s.cpu?.utilizationPercent ?? null}
              history={cpuHist}
              color="#5dffb0"
              sub={s.cpu?.loadAverage1m != null ? `load ${s.cpu.loadAverage1m.toFixed(2)}` : ''}
            />
            <Gauge
              label="ram"
              value={s.ram?.usedMb != null ? `${(s.ram.usedMb / 1024).toFixed(1)}` : '—'}
              unit="gb"
              bar={ramPct}
              history={ramHist}
              color="#7ec6ff"
              sub={s.ram?.totalMb != null ? `of ${(s.ram.totalMb / 1024).toFixed(1)}` : ''}
            />
            <Gauge
              label="temp"
              value={s.gpu?.temperatureC != null ? `${s.gpu.temperatureC}` : '—'}
              unit="°c"
              bar={
                s.gpu?.temperatureC != null ? Math.min(100, (s.gpu.temperatureC / 95) * 100) : null
              }
              barTone={
                s.gpu?.temperatureC != null
                  ? s.gpu.temperatureC > 85
                    ? 'danger'
                    : s.gpu.temperatureC > 75
                      ? 'warn'
                      : null
                  : null
              }
              history={tempHist}
              color="#f5b14c"
              sub="gpu core"
            />
            <Gauge
              label="power"
              value={s.gpu?.powerW != null ? `${s.gpu.powerW.toFixed(0)}` : '—'}
              unit="w"
              bar={s.gpu?.powerW != null ? Math.min(100, (s.gpu.powerW / 175) * 100) : null}
              history={powerHist}
              color="#c4a8ff"
              sub="gpu draw"
            />
          </div>
        </div>
      </section>

      {/* ===== Live log tail (was its own page) ===== */}
      <LogStreamPanel headerStep="C" />
    </div>
  )
}

function Gauge({
  label,
  value,
  unit,
  sub,
  bar,
  barTone,
  history,
  color,
}: {
  label: string
  value: string
  unit?: string
  sub?: string
  bar: number | null
  barTone?: 'warn' | 'danger' | null
  history: (number | null)[]
  color: string
}) {
  return (
    <div className="gauge">
      <span className="lbl">{label}</span>
      <span className="val tabular">
        {value}
        {unit && (
          <span className="dim" style={{ fontSize: '0.7em', marginLeft: '0.18rem' }}>
            {unit}
          </span>
        )}
      </span>
      {bar != null && (
        <span
          className={
            'linear-bar ' + (barTone === 'danger' ? 'danger' : barTone === 'warn' ? 'warn' : '')
          }
        >
          <span style={{ width: `${Math.max(0, Math.min(100, bar))}%` }} />
        </span>
      )}
      <div className="sparkline-host">
        <Sparkline data={history} color={color} fill={color + '28'} height={28} />
      </div>
      {sub && <span className="sub">{sub}</span>}
    </div>
  )
}
