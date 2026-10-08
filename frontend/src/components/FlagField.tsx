import type { ReactNode } from 'react'
import { HelpTip } from './HelpTip'
import { FLAG_HELP } from '../lib/flagHelp'
import type { LlamaFlags } from '../types'

type CommonProps = {
  flagKey: keyof LlamaFlags
  label: string
  hint?: string
}

export function NumField({
  flagKey,
  label,
  value,
  onChange,
  placeholder,
}: CommonProps & {
  value: number | null | undefined
  onChange: (v: number | null) => void
  placeholder?: string
}) {
  const help = FLAG_HELP[flagKey]
  return (
    <label className="field">
      <span className="field-label">
        {label}
        {help?.special && <span className="cap-live">⚡ extra</span>}
        {help && <HelpTip help={help} />}
      </span>
      <input
        className="field-input"
        type="number"
        value={value == null ? '' : value}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        placeholder={placeholder ?? help?.default ?? ''}
      />
    </label>
  )
}

export function TextField({
  flagKey,
  label,
  value,
  onChange,
  placeholder,
  type = 'text',
}: CommonProps & {
  value: string | null | undefined
  onChange: (v: string | null) => void
  placeholder?: string
  type?: string
}) {
  const help = FLAG_HELP[flagKey]
  return (
    <label className="field">
      <span className="field-label">
        {label}
        {help?.special && <span className="cap-live">⚡ extra</span>}
        {help && <HelpTip help={help} />}
      </span>
      <input
        className="field-input"
        type={type}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)}
        placeholder={placeholder ?? help?.default ?? ''}
      />
    </label>
  )
}

export function SelectField({
  flagKey,
  label,
  value,
  onChange,
  options,
}: CommonProps & {
  value: string | null | undefined
  onChange: (v: string | null) => void
  options: { value: string; label?: string }[]
}) {
  const help = FLAG_HELP[flagKey]
  return (
    <label className="field">
      <span className="field-label">
        {label}
        {help?.special && <span className="cap-live">⚡ extra</span>}
        {help && <HelpTip help={help} />}
      </span>
      <select
        className="field-select"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? null : e.target.value)}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label ?? (o.value === '' ? '— default —' : o.value)}
          </option>
        ))}
      </select>
    </label>
  )
}

export function ToggleField({
  flagKey,
  label,
  desc,
  value,
  onChange,
}: {
  flagKey: keyof LlamaFlags
  label: string
  desc?: string
  value: boolean | null | undefined
  onChange: (v: boolean) => void
}) {
  const help = FLAG_HELP[flagKey]
  const special = help?.special
  return (
    <button
      type="button"
      className={'toggle' + (value ? ' on' : '') + (special ? ' special' : '')}
      onClick={() => onChange(!value)}
    >
      <span className="switch" />
      <span className="body">
        <span className="label">
          {label}
          {special && <span className="cap">⚡ extra</span>}
        </span>
        {(desc ?? help?.what) && <span className="desc">{desc ?? help?.what}</span>}
      </span>
      {help && (
        <span onClick={(e) => e.stopPropagation()} style={{ alignSelf: 'flex-start' }}>
          <HelpTip help={help} />
        </span>
      )}
    </button>
  )
}

export function SectionHeader({
  step,
  title,
  meta,
}: {
  step?: string
  title: string
  meta?: ReactNode
}) {
  return (
    <div className="section-header">
      {step && <span className="step">{step}</span>}
      <span className="title">{title}</span>
      <span className="rule" />
      {meta && <span className="meta">{meta}</span>}
    </div>
  )
}
