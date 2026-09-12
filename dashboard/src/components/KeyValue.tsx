import type { ReactNode } from 'react';

/** A definition list for "name: value" blocks, which is what most of the System page is. */
export function KeyValue({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="kv">
      {items.map((item) => (
        <div className="kv__row" key={item.label}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}
