import type { ReactNode } from 'react';
import type { Tone } from '../format';

/**
 * A status, said twice: once in colour and once in words.
 *
 * <p>The colour is decoration. The text inside the badge is the status, and a glyph reinforces it,
 * so the badge is readable with any colour vision, in greyscale, and by a screen reader. That is
 * the rule the whole dashboard follows — colour never carries meaning on its own.
 */

const GLYPH: Record<Tone, string> = {
  ok: '\u25CF',
  info: '\u25B6',
  warn: '\u25B2',
  error: '\u2716',
  unknown: '\u2014'
};

interface Props {
  tone: Tone;
  children: ReactNode;
  title?: string;
}

export function StatusBadge({ tone, children, title }: Props) {
  return (
    <span className={`badge badge--${tone}`} title={title}>
      <span className="badge__glyph" aria-hidden="true">
        {GLYPH[tone]}
      </span>
      <span className="badge__text">{children}</span>
    </span>
  );
}
