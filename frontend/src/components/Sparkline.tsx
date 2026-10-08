type Props = {
  data: (number | null)[]
  color?: string
  fill?: string
  height?: number
}

export function Sparkline({ data, color = '#6ad4ff', fill = 'rgba(106,212,255,0.15)', height = 32 }: Props) {
  const points = data.filter((v): v is number => v != null && Number.isFinite(v))
  if (points.length < 2) return <svg className="sparkline" viewBox={`0 0 100 ${height}`} preserveAspectRatio="none" />

  const max = Math.max(...points, 0.0001)
  const min = Math.min(...points)
  const range = max - min || 1
  const step = 100 / (points.length - 1)
  const path = points
    .map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(2)},${(height - ((v - min) / range) * height).toFixed(2)}`)
    .join(' ')

  const area = `${path} L100,${height} L0,${height} Z`

  return (
    <svg className="sparkline" viewBox={`0 0 100 ${height}`} preserveAspectRatio="none">
      <path d={area} fill={fill} stroke="none" />
      <path d={path} fill="none" stroke={color} strokeWidth={1.4} vectorEffect="non-scaling-stroke" />
    </svg>
  )
}
