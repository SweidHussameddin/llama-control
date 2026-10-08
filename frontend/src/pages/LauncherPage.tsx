import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import {
  DEFAULT_FLAGS,
  type LlamaFlags,
  type ModelDetails,
  type ModelFile,
  type Profile,
  type ServerStatus,
} from '../types'
import {
  NumField,
  TextField,
  SelectField,
  ToggleField,
  SectionHeader,
} from '../components/FlagField'
import { RangeField } from '../components/RangeField'
import { renderArgs } from '../lib/renderArgs'
import { FLAG_HELP } from '../lib/flagHelp'
import { HelpTip } from '../components/HelpTip'
import { aliasFromPath } from '../lib/aliasFromPath'

type Props = {
  status: ServerStatus | null
  onStatusChange: (s: ServerStatus) => void
}

const CACHE_TYPES = [
  { value: 'f16', label: 'f16  · full' },
  { value: 'f32', label: 'f32  · oversized' },
  { value: 'q8_0', label: 'q8_0 · safe' },
  { value: 'q5_1', label: 'q5_1' },
  { value: 'q5_0', label: 'q5_0' },
  { value: 'q4_1', label: 'q4_1' },
  { value: 'q4_0', label: 'q4_0 · smallest' },
]

const ROPE_SCALING = [
  { value: '', label: '— auto —' },
  { value: 'none', label: 'none' },
  { value: 'linear', label: 'linear' },
  { value: 'yarn', label: 'yarn' },
]

const CTX_TICKS = [2048, 4096, 8192, 16384, 32768, 65536, 131072, 262144]
const BATCH_TICKS = [512, 1024, 2048, 4096, 8192]
const PARALLEL_TICKS = [1, 2, 4, 8]
const THREAD_TICKS = [4, 8, 12, 16, 24, 32]
const CACHE_REUSE_TICKS = [0, 64, 128, 256, 512, 1024]

const TOTAL_FLAGS = Object.keys(FLAG_HELP).length

export function LauncherPage({ status, onStatusChange }: Props) {
  const [models, setModels] = useState<ModelFile[]>([])
  const [profiles, setProfiles] = useState<Profile[]>([])
  // Details are stored together with the path they belong to, so a model change shows no stale
  // details while the new ones load, without resetting state inside an effect.
  const [detailsFor, setDetailsFor] = useState<{ path: string; details: ModelDetails | null } | null>(null)
  const [flags, setFlags] = useState<LlamaFlags>(DEFAULT_FLAGS)
  const runningFlags: LlamaFlags | null = status?.running && status.startedFlags ? status.startedFlags : null
  const details = detailsFor && detailsFor.path === flags.modelPath ? detailsFor.details : null
  const [locked, setLocked] = useState<boolean>(false)
  const [selectedProfileId, setSelectedProfileId] = useState<string>('')
  const [profileName, setProfileName] = useState<string>('')
  const [profileId, setProfileId] = useState<string>('')
  const [extraArgsRaw, setExtraArgsRaw] = useState<string>('')
  const [showAdvanced, setShowAdvanced] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    api.listModels().then(setModels).catch((e) => setError(String(e)))
    api.listProfiles().then(setProfiles).catch((e) => setError(String(e)))
  }, [])

  // === Sync with the running server ===
  // Server status is external state; the form adopts it when it changes. Done as a state
  // adjustment during render (React's "storing information from previous renders" pattern)
  // rather than in an effect. The first observed running server locks the form onto its config;
  // afterwards the form follows the server only while locked, so edits are never overwritten.
  const [seenStatus, setSeenStatus] = useState<ServerStatus | null | undefined>(undefined)
  const [initialized, setInitialized] = useState(false)
  if (status !== seenStatus) {
    setSeenStatus(status)
    if (status?.running && status.startedFlags) {
      if (!initialized || locked) {
        setFlags(status.startedFlags)
        setExtraArgsRaw((status.startedFlags.extraArgs ?? []).join(' '))
        setProfileId(status.profileId ?? '')
      }
      if (!initialized) {
        setInitialized(true)
        setLocked(true)
      }
    } else if (status && !status.running) {
      if (!initialized) setInitialized(true)
      // unlock when the server stops
      if (locked) setLocked(false)
    }
  }

  // Fetch model details + auto-fill alias on model change
  useEffect(() => {
    const path = flags.modelPath
    if (!path) return
    let cancelled = false
    api
      .modelDetails(path)
      .then((d) => {
        if (!cancelled) setDetailsFor({ path, details: d })
      })
      .catch(() => {
        if (!cancelled) setDetailsFor({ path, details: null })
      })
    return () => {
      cancelled = true
    }
  }, [flags.modelPath])

  const running = status?.running ?? false
  const ready = !running && !busy && !!flags.modelPath

  // Detect whether form differs from what's currently running
  const hasChanges = useMemo(() => {
    if (!runningFlags) return false
    const formForCompare = {
      ...flags,
      extraArgs: extraArgsRaw.trim() ? extraArgsRaw.trim().split(/\s+/) : null,
    }
    return JSON.stringify(formForCompare) !== JSON.stringify(runningFlags)
  }, [flags, extraArgsRaw, runningFlags])

  // model-aware caps
  const ctxMax = details?.contextLength ?? 262144
  const layersMax = details?.blockCount ?? 99

  const update = <K extends keyof LlamaFlags>(key: K, value: LlamaFlags[K]) => {
    setFlags((prev) => ({ ...prev, [key]: value }))
  }

  // Picking a model also fills the alias from the file name; a hand-edited alias is kept.
  const onSelectModel = (path: string | null) => {
    const alias = path ? aliasFromPath(path) : null
    setFlags((prev) => ({ ...prev, modelPath: path, ...(alias ? { alias } : {}) }))
  }

  const onSelectProfile = async (id: string) => {
    setSelectedProfileId(id)
    if (!id) return
    const p = profiles.find((x) => x.id === id)
    if (!p) return
    setFlags(p.flags)
    setProfileId(p.id)
    setProfileName(p.name)
    setExtraArgsRaw((p.flags.extraArgs ?? []).join(' '))
  }

  const flagsForSubmit: LlamaFlags = useMemo(
    () => ({
      ...flags,
      extraArgs: extraArgsRaw.trim() ? extraArgsRaw.trim().split(/\s+/) : null,
    }),
    [flags, extraArgsRaw],
  )

  const cliArgs = useMemo(
    () => renderArgs(flagsForSubmit, details?.architecture),
    [flagsForSubmit, details?.architecture],
  )
  const flagsSet = useMemo(() => cliArgs.filter((s) => s.startsWith('-')).length, [cliArgs])

  const copyCli = async () => {
    try {
      await navigator.clipboard.writeText(['llama-server-cuda', ...cliArgs].join(' '))
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // ignore
    }
  }

  const saveProfile = async () => {
    setError(null)
    if (!profileId.trim() || !profileName.trim()) {
      setError('Profile needs both an id (kebab-case) and a display name.')
      return
    }
    try {
      const saved = await api.saveProfile({
        id: profileId.trim(),
        name: profileName.trim(),
        flags: flagsForSubmit,
      })
      const list = await api.listProfiles()
      setProfiles(list)
      setSelectedProfileId(saved.id)
    } catch (e) {
      setError(String(e))
    }
  }

  const start = async () => {
    setError(null)
    setBusy(true)
    try {
      const s = await api.startServerInline({
        id: profileId || 'inline',
        name: profileName || 'Inline',
        flags: flagsForSubmit,
      })
      onStatusChange(s)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  const stop = async () => {
    setError(null)
    setBusy(true)
    try {
      const s = await api.stopServer()
      onStatusChange(s)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  const reload = async () => {
    setError(null)
    setBusy(true)
    try {
      await api.stopServer()
      const s = await api.startServerInline({
        id: profileId || 'inline',
        name: profileName || 'Inline',
        flags: flagsForSubmit,
      })
      onStatusChange(s)
      setLocked(true)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  const discardChanges = () => {
    if (!runningFlags) return
    setFlags(runningFlags)
    setExtraArgsRaw((runningFlags.extraArgs ?? []).join(' '))
    setLocked(true)
  }

  const deleteProfile = async () => {
    if (!selectedProfileId) return
    if (!confirm(`Delete profile "${selectedProfileId}"?`)) return
    await api.deleteProfile(selectedProfileId)
    const list = await api.listProfiles()
    setProfiles(list)
    setSelectedProfileId('')
  }

  const modelFile = useMemo(
    () => models.find((m) => m.path === flags.modelPath) ?? null,
    [models, flags.modelPath],
  )

  return (
    <div className="launcher">
      <div className={'launcher-body' + (locked ? ' locked' : '')}>
        {error && (
          <div className="banner error">
            <span className="glyph">!</span>
            <span>{error}</span>
          </div>
        )}

        {locked && (
          <div className="lock-banner">
            <span className="glyph">⊘</span>
            <span className="body">
              <strong>READ-ONLY</strong> — showing flags the running llama-server was started with
              <span className="dim">  ·  pid {status?.pid}</span>
            </span>
            <button className="btn" onClick={() => setLocked(false)}>
              ✎ Modify
            </button>
          </div>
        )}
        {!locked && running && hasChanges && (
          <div className="lock-banner changes-banner">
            <span className="glyph">⚡</span>
            <span className="body">
              <strong>UNSAVED CHANGES</strong> — flags differ from what's running. Click{' '}
              <strong>Reload</strong> to apply, <strong>Discard</strong> to revert.
            </span>
            <button className="btn" onClick={discardChanges}>
              ↶ Discard
            </button>
          </div>
        )}
        {!locked && running && !hasChanges && (
          <div className="lock-banner">
            <span className="glyph">✎</span>
            <span className="body">
              <strong>EDITING</strong> — make changes then Reload, or re-lock to read-only.
            </span>
            <button className="btn" onClick={() => setLocked(true)}>
              ⊘ Lock
            </button>
          </div>
        )}

        {/* ============ Step 01 — Target ============ */}
        <section>
          <SectionHeader step="01" title="Target" meta={`${models.length} models discovered`} />
          <div className="step-block boxed" style={{ position: 'relative' }}>
            <span className="panel-label">model</span>
            <div className="field-row cols-2">
              <label className="field" style={{ gridColumn: 'span 2' }}>
                <span className="field-label">
                  GGUF model file <span className="req">*</span>
                  {FLAG_HELP.modelPath && <HelpTip help={FLAG_HELP.modelPath} />}
                </span>
                <select
                  className="field-select"
                  value={flags.modelPath ?? ''}
                  onChange={(e) => onSelectModel(e.target.value || null)}
                >
                  <option value="">— pick a model —</option>
                  {models.map((m) => (
                    <option key={m.path} value={m.path}>
                      {m.name}  ·  {(m.sizeBytes / 1e9).toFixed(1)} GB
                    </option>
                  ))}
                </select>
              </label>
              <TextField
                flagKey="alias"
                label="Alias (auto-derived — editable)"
                value={flags.alias}
                onChange={(v) => update('alias', v)}
              />
              {details && (
                <div className="field">
                  <span className="field-label">model info</span>
                  <div className="mono" style={{ fontSize: '0.8rem', color: 'var(--text-2)', lineHeight: 1.55 }}>
                    <span className="amber">{details.architecture}</span> ·{' '}
                    {details.contextLength?.toLocaleString() ?? '—'} tok ctx · {details.blockCount} layers
                  </div>
                </div>
              )}
            </div>
          </div>
        </section>

        {/* ============ Step 02 — Performance ============ */}
        <section>
          <SectionHeader step="02" title="Performance" />
          <div className="step-block">
            <div className="field-row cols-2">
              <RangeField
                flagKey="ctxSize"
                label="Context length"
                value={flags.ctxSize}
                onChange={(v) => update('ctxSize', v)}
                min={512}
                max={ctxMax}
                step={512}
                ticks={CTX_TICKS}
                maxLabel={`up to ${(ctxMax / 1024).toFixed(0)}k tok`}
                suffix="tok"
              />
              <RangeField
                flagKey="gpuLayers"
                label="GPU offload (layers)"
                value={flags.gpuLayers}
                onChange={(v) => update('gpuLayers', v)}
                min={0}
                max={Math.max(layersMax, 99)}
                step={1}
                ticks={[0, Math.floor(layersMax / 4), Math.floor(layersMax / 2), Math.floor(layersMax * 3 / 4), layersMax]}
                maxLabel={`${layersMax} layers`}
              />
              <RangeField
                flagKey="threads"
                label="CPU threads"
                value={flags.threads}
                onChange={(v) => update('threads', v)}
                min={1}
                max={32}
                step={1}
                ticks={THREAD_TICKS}
              />
              <RangeField
                flagKey="parallel"
                label="Concurrent slots"
                value={flags.parallel}
                onChange={(v) => update('parallel', v)}
                min={1}
                max={8}
                step={1}
                ticks={PARALLEL_TICKS}
              />
              <RangeField
                flagKey="batchSize"
                label="Logical batch (-b)"
                value={flags.batchSize}
                onChange={(v) => update('batchSize', v)}
                min={64}
                max={8192}
                step={64}
                ticks={BATCH_TICKS}
                suffix="tok"
              />
              <RangeField
                flagKey="ubatchSize"
                label="Physical batch (-ub)"
                value={flags.ubatchSize}
                onChange={(v) => update('ubatchSize', v)}
                min={64}
                max={8192}
                step={64}
                ticks={BATCH_TICKS}
                suffix="tok"
              />
            </div>
            <div className="field-row cols-2">
              <NumField flagKey="seed" label="RNG seed" value={flags.seed} onChange={(v) => update('seed', v)} />
              <NumField
                flagKey="keep"
                label="Keep first N tokens"
                value={flags.keep}
                onChange={(v) => update('keep', v)}
              />
            </div>
          </div>
        </section>

        {/* ============ Step 03 — Memory & MoE ============ */}
        <section>
          <SectionHeader step="03" title="Memory & MoE" />
          <div className="step-block">
            <div className="subpanel">
              <div className="subpanel-title">Mixture of Experts</div>
              <div className="field-row cols-2">
                {details?.expertCount != null && details.expertCount > 0 ? (
                  <RangeField
                    flagKey="expertsUsed"
                    label="Experts used per token"
                    value={flags.expertsUsed ?? details.expertUsedCount ?? null}
                    onChange={(v) => update('expertsUsed', v)}
                    min={1}
                    max={details.expertCount}
                    step={1}
                    ticks={Array.from(
                      new Set([
                        1,
                        Math.max(1, Math.floor((details.expertUsedCount ?? 8) / 2)),
                        details.expertUsedCount ?? 8,
                        details.expertCount,
                      ]),
                    )}
                    maxLabel={`${details.expertCount} total · ${details.expertUsedCount ?? '?'} default`}
                  />
                ) : (
                  <NumField
                    flagKey="expertsUsed"
                    label="Experts used per token"
                    value={flags.expertsUsed}
                    onChange={(v) => update('expertsUsed', v)}
                  />
                )}
                <RangeField
                  flagKey="cpuMoeLayers"
                  label="MoE experts → CPU (first N layers)"
                  value={flags.cpuMoeLayers}
                  onChange={(v) => update('cpuMoeLayers', v)}
                  min={0}
                  max={layersMax}
                  step={1}
                  ticks={[0, Math.floor(layersMax / 4), Math.floor(layersMax / 2), Math.floor(layersMax * 3 / 4), layersMax]}
                  maxLabel={`${layersMax} layers`}
                />
              </div>
              <div className="toggle-row dense" style={{ marginTop: '0.7rem' }}>
                <ToggleField
                  flagKey="cpuMoe"
                  label="All MoE experts → CPU"
                  value={flags.cpuMoe}
                  onChange={(v) => update('cpuMoe', v)}
                />
                <ToggleField
                  flagKey="mlock"
                  label="Keep model in RAM (mlock)"
                  value={flags.mlock}
                  onChange={(v) => update('mlock', v)}
                />
                <ToggleField
                  flagKey="noMmap"
                  label="Disable memory-map loading"
                  value={flags.noMmap}
                  onChange={(v) => update('noMmap', v)}
                />
              </div>
            </div>
            <div className="subpanel">
              <div className="subpanel-title">KV cache</div>
              <div className="field-row cols-2">
                <SelectField
                  flagKey="cacheTypeK"
                  label="K cache type"
                  value={flags.cacheTypeK}
                  onChange={(v) => update('cacheTypeK', v)}
                  options={CACHE_TYPES}
                />
                <SelectField
                  flagKey="cacheTypeV"
                  label="V cache type"
                  value={flags.cacheTypeV}
                  onChange={(v) => update('cacheTypeV', v)}
                  options={CACHE_TYPES}
                />
              </div>
              <div className="toggle-row dense" style={{ marginTop: '0.7rem' }}>
                <ToggleField
                  flagKey="flashAttn"
                  label="Flash Attention"
                  value={flags.flashAttn}
                  onChange={(v) => update('flashAttn', v)}
                />
                <ToggleField
                  flagKey="kvUnified"
                  label="Unified KV cache"
                  value={flags.kvUnified}
                  onChange={(v) => update('kvUnified', v)}
                />
                <ToggleField
                  flagKey="noKvOffload"
                  label="Disable KV → GPU"
                  value={flags.noKvOffload}
                  onChange={(v) => update('noKvOffload', v)}
                />
              </div>
            </div>
          </div>
        </section>

        {/* ============ Step 05 — Caching & Server (the hero panel) ============ */}
        <section>
          <SectionHeader
            step="04"
            title="Caching & Server"
            meta={<span className="live">⚡ flags LM Studio doesn’t expose</span>}
          />
          <div className="step-block boxed" style={{ position: 'relative', background: 'linear-gradient(180deg, rgba(93,255,176,0.04), var(--panel-2))', borderColor: 'rgba(93,255,176,0.25)' }}>
            <span className="panel-label live">⚡ extra</span>
            <p style={{ margin: '0 0 0.85rem 0', color: 'var(--text-2)', fontSize: '0.82rem', lineHeight: 1.55 }}>
              These flags are the reason this tool exists. Prefix caching and physical batch size
              are the single biggest levers for coding-agent latency — neither is reachable through
              the LM Studio UI.
            </p>
            <div className="field-row cols-2">
              <RangeField
                flagKey="cacheReuse"
                label="Prefix cache reuse"
                value={flags.cacheReuse}
                onChange={(v) => update('cacheReuse', v)}
                min={0}
                max={2048}
                step={64}
                ticks={CACHE_REUSE_TICKS}
                suffix="tok"
              />
              <NumField
                flagKey="port"
                label="Port"
                value={flags.port}
                onChange={(v) => update('port', v)}
              />
            </div>
            <div className="toggle-row dense" style={{ marginTop: '0.85rem' }}>
              <ToggleField
                flagKey="metrics"
                label="Expose /metrics"
                value={flags.metrics}
                onChange={(v) => update('metrics', v)}
              />
              <ToggleField
                flagKey="jinja"
                label="Use Jinja template"
                value={flags.jinja}
                onChange={(v) => update('jinja', v)}
              />
            </div>
            <div className="field-row cols-2" style={{ marginTop: '0.85rem' }}>
              <TextField
                flagKey="host"
                label="Host"
                value={flags.host}
                onChange={(v) => update('host', v)}
              />
              <TextField
                flagKey="apiKey"
                label="API key (optional)"
                value={flags.apiKey}
                onChange={(v) => update('apiKey', v)}
                type="password"
              />
            </div>
          </div>
        </section>

        {/* ============ Advanced ============ */}
        <section>
          <button
            className="btn ghost"
            onClick={() => setShowAdvanced((v) => !v)}
            style={{ padding: '0.4rem 0.7rem' }}
          >
            {showAdvanced ? '▾' : '▸'} advanced — RoPE & escape hatch
          </button>
          {showAdvanced && (
            <div className="step-block" style={{ marginTop: '0.75rem' }}>
              <div className="subpanel">
                <div className="subpanel-title">RoPE (context extension)</div>
                <div className="field-row cols-3">
                  <NumField
                    flagKey="ropeFreqBase"
                    label="rope-freq-base"
                    value={flags.ropeFreqBase}
                    onChange={(v) => update('ropeFreqBase', v)}
                  />
                  <NumField
                    flagKey="ropeFreqScale"
                    label="rope-freq-scale"
                    value={flags.ropeFreqScale}
                    onChange={(v) => update('ropeFreqScale', v)}
                  />
                  <SelectField
                    flagKey="ropeScaling"
                    label="rope-scaling"
                    value={flags.ropeScaling}
                    onChange={(v) => update('ropeScaling', v)}
                    options={ROPE_SCALING}
                  />
                </div>
              </div>
              <div className="subpanel">
                <div className="subpanel-title">Extra args (escape hatch)</div>
                <label className="field">
                  <span className="field-label">
                    Appended verbatim
                    {FLAG_HELP.extraArgs && <HelpTip help={FLAG_HELP.extraArgs} />}
                  </span>
                  <textarea
                    className="field-input"
                    rows={2}
                    value={extraArgsRaw}
                    onChange={(e) => setExtraArgsRaw(e.target.value)}
                    placeholder='--no-warmup --override-kv qwen3moe.expert_used_count=int:8'
                    style={{ fontFamily: 'var(--mono)', resize: 'vertical' }}
                  />
                </label>
              </div>
            </div>
          )}
        </section>
      </div>

      {/* ===========================================================
           STICKY RAIL — summary, CLI, actions, save
           =========================================================== */}
      <aside className="launcher-rail">
        <div className="summary-card">
          <div className="row">
            <span className="k">model</span>
            <span className="v">
              {modelFile
                ? modelFile.name
                : <span className="dim">— pick a model —</span>}
            </span>
          </div>
          <div className="row">
            <span className="k">size</span>
            <span className="v">{modelFile ? `${(modelFile.sizeBytes / 1e9).toFixed(1)} GB` : '—'}</span>
          </div>
          <div className="row">
            <span className="k">arch</span>
            <span className="v amber">{details?.architecture ?? '—'}</span>
          </div>
          <div className="row">
            <span className="k">ctx</span>
            <span className="v">
              <span className="amber tabular">{flags.ctxSize?.toLocaleString() ?? '—'}</span>
              <span className="dim"> / {details?.contextLength?.toLocaleString() ?? '—'}</span>
            </span>
          </div>
          <div className="row">
            <span className="k">port</span>
            <span className="v">{flags.port ?? '—'}</span>
          </div>
          <div className="row">
            <span className="k">alias</span>
            <span className="v">{flags.alias ?? <span className="dim">—</span>}</span>
          </div>
          <div className="row">
            <span className="k">flags set</span>
            <span className="v">
              <span className="live tabular">{flagsSet}</span>
              <span className="dim"> / {TOTAL_FLAGS}</span>
            </span>
          </div>
        </div>

        <div className={'rail-actions' + (running && !locked ? ' three-col' : '')}>
          {!running && (
            <button className="btn live" onClick={start} disabled={!ready} style={{ gridColumn: 'span 2' }}>
              <span className="glyph">▶</span> Start
            </button>
          )}
          {running && locked && (
            <button className="btn" onClick={() => setLocked(false)}>
              <span className="glyph">✎</span> Modify
            </button>
          )}
          {running && !locked && (
            <button className="btn live" onClick={reload} disabled={!hasChanges || busy}>
              <span className="glyph">↻</span> Reload
            </button>
          )}
          {running && !locked && (
            <button className="btn" onClick={discardChanges} disabled={!hasChanges}>
              <span className="glyph">↶</span> Discard
            </button>
          )}
          {running && (
            <button className="btn danger" onClick={stop} disabled={busy}>
              <span className="glyph">■</span> Stop
            </button>
          )}
        </div>

        <div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: '0.5rem', marginBottom: '0.45rem' }}>
            <span className="mono" style={{ fontSize: '0.62rem', letterSpacing: '0.16em', color: 'var(--muted)', textTransform: 'uppercase' }}>
              command preview
            </span>
            <span className="rule" style={{ flex: 1, height: '1px', background: 'var(--border)' }} />
          </div>
          <div className="rail-cli">
            <button className="btn ghost rail-cli-copy" onClick={copyCli}>
              {copied ? '✓ copied' : '⧉ copy'}
            </button>
            <span className="bin">llama-server-cuda</span>{' '}
            {cliArgs.map((a, i) => (
              <span key={i} className={a.startsWith('-') ? 'flag' : 'val'}>
                {a}
                {i < cliArgs.length - 1 && ' '}
              </span>
            ))}
          </div>
        </div>

        <div className="summary-card">
          <div style={{ display: 'flex', alignItems: 'baseline', gap: '0.5rem', marginBottom: '0.5rem' }}>
            <span className="mono" style={{ fontSize: '0.62rem', letterSpacing: '0.16em', color: 'var(--muted)', textTransform: 'uppercase' }}>
              saved profiles
            </span>
            <span style={{ flex: 1, height: '1px', background: 'var(--border)' }} />
          </div>
          <select
            className="field-select"
            value={selectedProfileId}
            onChange={(e) => onSelectProfile(e.target.value)}
            style={{ marginBottom: '0.55rem' }}
          >
            <option value="">— load a saved profile —</option>
            {profiles.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
          <div style={{ display: 'flex', gap: '0.45rem', marginBottom: '0.85rem' }}>
            <button className="btn ghost" onClick={deleteProfile} disabled={!selectedProfileId} style={{ flex: 1 }}>
              delete
            </button>
          </div>
          <div style={{ display: 'grid', gap: '0.5rem' }}>
            <input
              className="field-input"
              value={profileId}
              onChange={(e) => setProfileId(e.target.value)}
              placeholder="id (kebab-case)"
            />
            <input
              className="field-input"
              value={profileName}
              onChange={(e) => setProfileName(e.target.value)}
              placeholder="display name"
            />
            <button className="btn primary" onClick={saveProfile}>
              ⌑ Save profile
            </button>
          </div>
        </div>
      </aside>
    </div>
  )
}
