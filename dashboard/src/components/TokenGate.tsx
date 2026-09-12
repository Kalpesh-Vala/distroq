import { useId, useState } from 'react';
import { setToken } from '../api/token';

/**
 * Where the operator types the token, and the only place it is entered.
 *
 * <p>`type="password"` so it is not shoulder-readable and not captured by a screenshot tool;
 * `autoComplete="off"` so a browser does not offer to save an operator credential into a profile
 * that syncs; and the form never navigates, so the value cannot end up in a query string.
 *
 * <p>The copy is honest about what this is. A shared bearer token held in a browser tab is a
 * development and small-deployment arrangement, not an identity system, and the operator reading
 * this panel is the person who should know that.
 */

interface Props {
  reason?: string | null;
}

export function TokenGate({ reason }: Props) {
  const [value, setValue] = useState('');
  const inputId = useId();

  return (
    <main className="gate" id="main">
      <h1>DistroQ operations</h1>
      {reason ? (
        <p className="notice notice--error" role="alert">
          {reason}
        </p>
      ) : null}
      <form
        className="gate__form"
        onSubmit={(event) => {
          event.preventDefault();
          setToken(value);
          setValue('');
        }}
      >
        <label htmlFor={inputId}>Administrative bearer token</label>
        <input
          id={inputId}
          name="token"
          type="password"
          autoComplete="off"
          spellCheck={false}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          aria-describedby={`${inputId}-help`}
        />
        <button type="submit" disabled={value.trim().length === 0}>
          Open dashboard
        </button>
      </form>
      <p className="gate__help" id={`${inputId}-help`}>
        The token is held in this tab&apos;s <code>sessionStorage</code> and is discarded when the
        tab closes. It is sent only as an <code>Authorization</code> header, never in a URL. This
        is a local-development and small-deployment arrangement: a production deployment should
        sit behind an identity-aware proxy or SSO layer instead. See README.md.
      </p>
    </main>
  );
}
