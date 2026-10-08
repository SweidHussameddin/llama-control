import { useMemo } from 'react'
import type { LlamaFlags } from '../types'
import { FLAG_HELP } from '../lib/flagHelp'
import { HelpTip } from './HelpTip'

type Props = {
  flagKey: keyof LlamaFlags
  label: string
  value: number | null | undefined
  onChange: (v: number | null) => void
  min?: number
  max: number
  step?: number
  /** Snap-points rendered as clickable ticks below the slider. */
  ticks?: number[]
  /** A short caption shown on the right of the label (e.g. "max 262 144") */
  maxLabel?: string
  /** A tone applied when value approaches max. */
  warningRatio?: number
  /** Suffix shown after the slider's numeric input (e.g. "tok"). */
  suffix?: string
}

function fmtTick(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`
  if (n >= 1024 && n % 1024 === 0) return `${n / 1024}k`
  if (n >= 10_000) return `${(n / 1000).toFixed(0)}k`
  return n.toString()
}

export function RangeField({
  flagKey,
  label,
  value,
  onChange,
  min = 0,
  max,
  step = 1,
  ticks,
  maxLabel,
  warningRatio = 0.92,
  suffix,
}: Props) {
  const help = FLAG_HELP[flagKey]
  const v = value ?? min
  const ratio = max > min ? (v - min) / (max - min) : 0
  const isHot = ratio >= warningRatio
  const effMax = maxLabel ?? `max ${fmtTick(max)}`

  const filteredTicks = useMemo(() => {
    if (!ticks) return undefined
    return Array.from(new Set(ticks.filter((t) => t >= min && t <= max))).sort((a, b) => a - b)
  }, [ticks, min, max])

  return (
    <div className="field range-field">
      <span className="field-label">
        <span className="field-label-text">
          {label}
          {help?.special && <span className="cap-live">⚡ extra</span>}
        </span>
        {help && <HelpTip help={help} />}
        <span className="max-info mono">{effMax}</span>
      </span>
      <div className="slider-row">
        <input
          type="range"
          className={'slider' + (isHot ? ' hot' : '')}
          min={min}
          max={max}
          step={step}
          value={v}
          onChange={(e) => onChange(Number(e.target.value))}
          style={{ '--ratio': `${ratio * 100}%` } as React.CSSProperties}
        />
        <input
          type="number"
          className={'field-input num-input' + (isHot ? ' hot' : '')}
          min={min}
          max={max}
          step={step}
          value={value == null ? '' : value}
          onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        />
        {suffix && <span className="num-suffix mono">{suffix}</span>}
      </div>
      {filteredTicks && filteredTicks.length > 0 && (
        <div className="tick-row">
          {filteredTicks.map((t) => (
            <button
              key={t}
              type="button"
              className={'tick' + (v === t ? ' active' : '')}
              onClick={() => onChange(t)}
            >
              {fmtTick(t)}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
