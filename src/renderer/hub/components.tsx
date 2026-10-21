import { useId, type ReactNode } from 'react'
import { cx } from './lib'

export function Toggle({
  checked,
  onChange,
  label
}: {
  checked: boolean
  onChange: (value: boolean) => void
  label: string
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      className={cx('toggle', checked && 'is-on')}
      onClick={() => onChange(!checked)}
    >
      <span className="toggle-knob" />
    </button>
  )
}

export function Section({
  title,
  description,
  children,
  aside
}: {
  title: string
  description?: string
  children: ReactNode
  aside?: ReactNode
}) {
  return (
    <section className="settings-section card card-flat">
      <header className="settings-section-head">
        <div>
          <h2 className="settings-section-title">{title}</h2>
          {description ? <p className="settings-section-desc">{description}</p> : null}
        </div>
        {aside}
      </header>
      <div className="settings-section-body">{children}</div>
    </section>
  )
}

export function SettingRow({
  label,
  hint,
  children,
  stacked = false
}: {
  label: string
  hint?: string
  children: ReactNode
  stacked?: boolean
}) {
  const labelId = useId()
  return (
    <div className={cx('setting-row', stacked && 'is-stacked')}>
      <div className="setting-copy">
        <div className="setting-label" id={labelId}>
          {label}
        </div>
        {hint ? <div className="setting-hint">{hint}</div> : null}
      </div>
      <div className="setting-control" role="group" aria-labelledby={labelId}>
        {children}
      </div>
    </div>
  )
}

export function Segmented<T extends string>({
  value,
  options,
  onChange
}: {
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div className="segmented" role="group">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          className={cx('segmented-item', value === option.value && 'is-active')}
          aria-pressed={value === option.value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

export function EmptyState({
  icon,
  title,
  body,
  action
}: {
  icon: ReactNode
  title: string
  body: string
  action?: ReactNode
}) {
  return (
    <div className="empty-state">
      <div className="empty-icon">{icon}</div>
      <h3 className="empty-title">{title}</h3>
      <p className="empty-body">{body}</p>
      {action}
    </div>
  )
}
