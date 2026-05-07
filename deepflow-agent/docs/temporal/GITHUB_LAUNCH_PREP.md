# GitHub launch preparation — three highest-leverage things

Notes from a session 2026-05-07 after UI polish wrapped (provenance
panel shipped, status bar in place, panel layout fits viewport, no
overflow). Functionality is enough for a public demo; the gap now is
*presentation* — how the repo reads to a first-time visitor on
github.com and on the README itself.

Ranked by impact for "bring it to the world":

---

## 1. Screenshots / GIFs in the README

**Why this is the single biggest dial.** The UI is now genuinely
polished — call tree with collapsible children, mutations panel,
watch panel with timeline ordering, origin panel with source / 
propagation / next-mutation framing — but none of it is visible from
the README. A first-time visitor reads the prose and forms a generic
"another Java tracing thing" impression.

A 5-second GIF showing one user flow does more than three paragraphs
of text. Suggested clip:

  open a session → expand a frame in the call tree → click ↤ origin
  on a value → see Source / Propagation / Mutation cards

Static screenshots are also fine and easier to host (no external
CDN — just commit `docs/img/*.png` and reference relatively from
the README). One screenshot per panel — Mutations, Watches, Origin —
gives anyone scrolling the README an instant mental model.

Recording tools (no recommendation, pick what you have): macOS
QuickTime → convert to GIF; Windows ScreenToGif; ffmpeg from a screen
recording. Target ~3-6 seconds, 800px wide, optimised for size.

## 2. Repo metadata on github.com itself

Ten minutes of work, big effect on click-through. People land on
github.com/gabert/deepflow before they ever see the README.

- **Short repo description** — one-line pitch in the GitHub repo
  description field. Something like: "Bytecode-instrumented Java
  tracing — captures arguments, return values, and silent mutations.
  See *what data* flowed, not just where time went."
- **Topics** (tags) — three or four to surface in search:
  `tracing`, `debugging`, `java`, `bytebuddy`, `clickhouse`,
  `observability`. Pick the audience.
- **Social preview image** — when someone shares the repo URL on
  X / Slack / HN, this is what's embedded. Without it the preview
  is generic. Even a simple 1280×640 with the logo + tagline beats
  nothing.

## 3. A "compared to" framing

The root README already has an *is NOT* section, which is good. One
step further: a tiny comparison table or two-paragraph breakdown of:

- *APM* tells you a request took 200ms.
- *Profiler* tells you the JIT compiled X.
- *Logs* tell you what someone thought to log.
- *DeepFlow* tells you the discount was applied to the wrong line
  item — even though no one logged it.

Helps the right person self-identify in 30 seconds. Equally important,
helps the **wrong** person bounce — someone landing thinking "oh
another APM" and discovering it's not will leave annoyed; someone
landing with a silent-data-bug problem and recognising themselves
becomes an advocate. Wrong audience leaving fast is a feature.

---

## Lower-leverage, can wait

These are responses to actual community pressure, not launch prep —
add when there's traffic justifying them:

- `CONTRIBUTING.md`
- Issue templates (bug report / feature request)
- Pull-request templates
- CI status badges (only meaningful if CI is wired up; if not, skip)
- `CODE_OF_CONDUCT.md` (only when there's a community to govern)

## What's already good and shouldn't be retouched

For reference — these don't need work:

- Root README structure (landing-page form, links into deepflow-agent/docs/).
- 60-second `docker compose up` demo. Already harden-tested for stock Linux.
- `is NOT` section. Sets honest expectations.
- Use cases, reading-a-trace, why-deepflow essays under
  `deepflow-agent/docs/`. Deep enough material for a curious reader.
- Wire-format spec under `deepflow-agent/docs/spec/`. Signals
  seriousness — most "tracing tools" don't publish a spec.

## TL;DR

If you only do one of the three: **do the GIF**. If you do two: GIF
plus repo metadata. The comparison framing can land in v2 of the
README after first reactions come in — you'll have real "what did
people think this was?" data to write against.
