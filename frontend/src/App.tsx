import { useEffect, useState } from 'react'
import './App.css'
import { api, streamMetrics } from './api'
import type { MetricsSnapshot, ServerStatus } from './types'
import { LauncherPage } from './pages/LauncherPage'
import { DashboardPage } from './pages/DashboardPage'
import { SlotsPage } from './pages/SlotsPage'

const TABS = [
  { id: 'launch', label: 'launch', sub: 'configure & run' },
  { id: 'telemetry', label: 'telemetry', sub: 'live metrics & logs' },
  { id: 'slots', label: 'slots', sub: 'kv cache state' },
] as const
type Tab = (typeof TABS)[number]['id']

const HISTORY_LIMIT = 90

function App() {
  const [tab, setTab] = useState<Tab>('launch')
  const [status, setStatus] = useState<ServerStatus | null>(null)
  const [snapshot, setSnapshot] = useState<MetricsSnapshot | null>(null)
  const [history, setHistory] = useState<MetricsSnapshot[]>([])

  useEffect(() => {
    api.serverStatus().then(setStatus).catch(() => {})
    const close = streamMetrics((s) => {
      setSnapshot(s)
      setHistory((prev) =>
        prev.length >= HISTORY_LIMIT
          ? [...prev.slice(prev.length - HISTORY_LIMIT + 1), s]
          : [...prev, s],
      )
    })
    const poll = setInterval(() => api.serverStatus().then(setStatus).catch(() => {}), 2000)
    return () => {
      close()
      clearInterval(poll)
    }
  }, [])

  const running = status?.running ?? false
  const reachable = snapshot?.serverReachable ?? false
  const activeMeta = TABS.find((t) => t.id === tab)!

  let statusPill: { cls: string; text: string }
  if (running && reachable) statusPill = { cls: 'pill live', text: `running · pid ${status!.pid}` }
  else if (running) statusPill = { cls: 'pill warn', text: 'starting…' }
  else statusPill = { cls: 'pill idle', text: 'standby' }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="mark">λ</div>
          <div>
            <div className="name">llama-control</div>
            <div className="sub">control plane</div>
          </div>
        </div>

        <div className="nav-section-label">operations</div>
        <nav className="nav">
          {TABS.map((t, i) => (
            <button
              key={t.id}
              className={'nav-item' + (tab === t.id ? ' active' : '')}
              onClick={() => setTab(t.id)}
            >
              <span className="idx">0{i + 1}</span>
              <span>
                <span className="badge" />
                {t.label}
              </span>
            </button>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="kv">
            <span>backend</span>
            <span>:8090</span>
          </div>
          <div className="kv">
            <span>gpu</span>
            <span>{snapshot?.gpu?.name?.replace('NVIDIA GeForce ', '').replace(' Laptop GPU', '') ?? '—'}</span>
          </div>
          <div className="kv">
            <span>vram</span>
            <span className="tabular">
              {snapshot?.gpu?.memoryUsedMb != null
                ? `${(snapshot.gpu.memoryUsedMb / 1024).toFixed(1)} / ${(snapshot.gpu!.memoryTotalMb! / 1024).toFixed(1)} GB`
                : '—'}
            </span>
          </div>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <span className="page-title">{activeMeta.label}</span>
          <span className="page-sub">{activeMeta.sub}</span>
          <span className="spacer" />
          <span className={statusPill.cls}>
            <span className="dot" />
            {statusPill.text}
          </span>
          {status?.baseUrl && (
            <span className="pill info" title="OpenAI-compatible endpoint">
              {status.baseUrl}
            </span>
          )}
        </header>

        {/*
          All three pages stay mounted across tab switches. Switching tabs only toggles a CSS
          class. This preserves Launcher form state, log-stream SSE connections, and history
          arrays — and prevents the log panel from re-receiving the entire backlog every time
          you change tabs.
        */}
        <div className={'page' + (tab === 'launch' ? ' active' : ' inactive')}>
          <LauncherPage status={status} onStatusChange={setStatus} />
        </div>
        <div
          className={
            'page telemetry-page' + (tab === 'telemetry' ? ' active' : ' inactive')
          }
        >
          <DashboardPage snapshot={snapshot} history={history} />
        </div>
        <div className={'page' + (tab === 'slots' ? ' active' : ' inactive')}>
          <SlotsPage snapshot={snapshot} />
        </div>
      </main>
    </div>
  )
}

export default App
