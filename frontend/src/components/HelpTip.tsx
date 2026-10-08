import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { FlagHelp } from '../lib/flagHelp'

type Props = {
  help: FlagHelp
}

const POP_WIDTH = 300
const POP_MAX_HEIGHT = 200
const GAP = 8
const MARGIN = 12

export function HelpTip({ help }: Props) {
  const [open, setOpen] = useState(false)
  const [pos, setPos] = useState<{ top: number; left: number; flipUp: boolean }>({
    top: 0,
    left: 0,
    flipUp: false,
  })
  const btnRef = useRef<HTMLButtonElement | null>(null)
  const popRef = useRef<HTMLDivElement | null>(null)

  const compute = useCallback(() => {
    const btn = btnRef.current
    if (!btn) return
    const r = btn.getBoundingClientRect()

    let left = r.left
    if (left + POP_WIDTH + MARGIN > window.innerWidth) {
      left = window.innerWidth - POP_WIDTH - MARGIN
    }
    if (left < MARGIN) left = MARGIN

    let top = r.bottom + GAP
    let flipUp = false
    if (top + POP_MAX_HEIGHT > window.innerHeight) {
      top = r.top - POP_MAX_HEIGHT - GAP
      flipUp = true
      if (top < MARGIN) top = MARGIN
    }

    setPos({ top, left, flipUp })
  }, [])

  useLayoutEffect(() => {
    if (open) compute()
  }, [open, compute])

  useEffect(() => {
    if (!open) return
    const onScroll = () => compute()
    const onResize = () => compute()
    const onClickOutside = (e: MouseEvent) => {
      if (
        btnRef.current &&
        !btnRef.current.contains(e.target as Node) &&
        popRef.current &&
        !popRef.current.contains(e.target as Node)
      ) {
        setOpen(false)
      }
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onResize)
    document.addEventListener('mousedown', onClickOutside)
    document.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onResize)
      document.removeEventListener('mousedown', onClickOutside)
      document.removeEventListener('keydown', onKey)
    }
  }, [open, compute])

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        className={'help-btn ' + (open ? 'open' : '')}
        onClick={(e) => {
          e.preventDefault()
          e.stopPropagation()
          setOpen((v) => !v)
        }}
        aria-label="What does this flag do?"
      >
        ?
      </button>
      {open &&
        createPortal(
          <div
            ref={popRef}
            className="help-pop"
            style={{
              position: 'fixed',
              top: pos.top,
              left: pos.left,
              width: POP_WIDTH,
            }}
            data-flip={pos.flipUp ? 'up' : 'down'}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="heading">flag reference</div>
            <div className="row">
              <span className="k">does</span>
              <span className="v">{help.what}</span>
            </div>
            <div className="row">
              <span className="k">when</span>
              <span className="v">{help.when}</span>
            </div>
            {help.default && (
              <div className="row">
                <span className="k">default</span>
                <span className="v">
                  <code>{help.default}</code>
                </span>
              </div>
            )}
          </div>,
          document.body,
        )}
    </>
  )
}
