import { useEffect, useState } from 'react';
import { SECTIONS, hrefFor, type SectionName } from '../hooks/useHashRoute';

const LABELS: Record<SectionName, string> = {
  overview: 'Overview',
  queues: 'Queues',
  workers: 'Workers',
  outbox: 'Outbox',
  reconciliation: 'Reconciliation',
  jobs: 'Jobs',
  dlq: 'DLQ',
  analytics: 'Analytics',
  system: 'System'
};

/**
 * Responsive navigation that is a list of links at every width.
 *
 * <p>On a narrow viewport it collapses behind a disclosure button rather than turning into a
 * different control: the same `<nav><ul><li><a>` is inside, so keyboard order, `aria-current` and
 * screen-reader semantics do not change with the screen size. The button reports its own state
 * with `aria-expanded` and the menu closes on selection, which is what a phone user expects.
 */
export function Nav({ current }: { current: SectionName }) {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    setOpen(false);
  }, [current]);

  return (
    <nav className="nav" aria-label="Dashboard sections">
      <button
        type="button"
        className="nav__toggle"
        aria-expanded={open}
        aria-controls="nav-sections"
        onClick={() => setOpen((value) => !value)}
      >
        Sections
      </button>
      <ul id="nav-sections" className={open ? 'nav__list nav__list--open' : 'nav__list'}>
        {SECTIONS.map((section) => (
          <li key={section}>
            <a
              href={hrefFor(section)}
              aria-current={section === current ? 'page' : undefined}
              onClick={() => setOpen(false)}
            >
              {LABELS[section]}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
