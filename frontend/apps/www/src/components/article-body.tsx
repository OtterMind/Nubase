import type { Block } from '@/lib/content';

export function ArticleBody({ body }: { body: Block[] }) {
  return (
    <div className="space-y-5">
      {body.map((block, i) => {
        if ('h2' in block) {
          return (
            <h2 key={i} className="font-display text-2xl font-bold text-[var(--nb-ink)]">
              {block.h2}
            </h2>
          );
        }
        if ('code' in block) {
          return (
            <pre
              key={i}
              className="overflow-x-auto rounded-xl border border-faint bg-[var(--nb-bg-2)]/70 px-5 py-4 font-mono text-[13px] leading-7 text-[var(--nb-ink)]"
            >
              {block.code}
            </pre>
          );
        }
        if ('ul' in block) {
          return (
            <ul key={i} className="space-y-2 text-[var(--nb-dim)]">
              {block.ul.map((item) => (
                <li key={item} className="flex items-start gap-3">
                  <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-[var(--nb-green)]" />
                  {item}
                </li>
              ))}
            </ul>
          );
        }
        return (
          <p key={i} className="text-lg leading-8 text-[var(--nb-dim)]">
            {block.p}
          </p>
        );
      })}
    </div>
  );
}
