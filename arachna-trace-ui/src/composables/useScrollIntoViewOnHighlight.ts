import { onMounted, watch } from 'vue';
import type { ComputedRef, Ref } from 'vue';

export interface UseScrollIntoViewOnHighlightOptions {
  // Run on mount as well — needed by JsonTree's freshly-mounted node
  // case (an ancestor expanded as part of a navigation, this node
  // mounted with isMatch already true). FrameCard rows are mounted
  // before the highlight ever fires so they don't need this.
  runOnMount?: boolean;
  // When true, scroll the first element child instead of the wrapper.
  // JsonTree wants this — its outer `.jt-node` includes all expanded
  // descendants, so its bounding box is huge; scrolling the inner
  // `.jt-row` is what actually lands the visible row in view.
  scrollFirstChild?: boolean;
}

// Two coverage paths for "scroll the matching element into view":
//   - isMatch transition (post-flush): catches highlight/clear changes
//   - tick bump: catches re-highlights of the same target where
//     isMatch didn't transition, plus layout shifts that pushed the
//     target off-screen between the click and now
// Optional onMount path covers the case where the element mounts with
// isMatch already true (an ancestor expanded as part of a programmatic
// navigation). Skips when the target is already fully visible inside
// its scrollable ancestor — re-scrolling a row the user can already
// see is just visual noise.
export function useScrollIntoViewOnHighlight(
  elRef: Ref<HTMLElement | null>,
  isMatch: Readonly<Ref<boolean>> | ComputedRef<boolean>,
  tick: Readonly<Ref<number>> | ComputedRef<number>,
  opts: UseScrollIntoViewOnHighlightOptions = {}
): void {
  function scrollSelfIfMatching(): void {
    if (!isMatch.value || !elRef.value) return;
    // Defer the geometry read + scroll to the next animation frame.
    // Vue's `flush: 'post'` runs after the DOM update queue, but the
    // browser's layout pass may not have settled yet — particularly
    // when this watcher fires inside a freshly-expanded ancestor.
    // Reading getBoundingClientRect() at that moment can return stale
    // or zero rects, and the "already visible" check bails on a false
    // positive. After one rAF the browser has computed layout; rect /
    // visibility / scrollIntoView all see honest numbers.
    requestAnimationFrame(() => {
      if (!isMatch.value || !elRef.value) return;
      const target = opts.scrollFirstChild
        ? ((elRef.value.firstElementChild as HTMLElement | null) || elRef.value)
        : elRef.value;
      if (isFullyVisible(target)) return;
      target.scrollIntoView({ block: 'center' });
    });
  }

  if (opts.runOnMount) {
    // Replaces an older `immediate: true` watch — that fired during
    // setup() while the template ref was still null and silently
    // bailed. onMounted gives us a real DOM node.
    onMounted(scrollSelfIfMatching);
  }
  watch(isMatch, scrollSelfIfMatching, { flush: 'post' });
  watch(tick, scrollSelfIfMatching, { flush: 'post' });
}

function isFullyVisible(el: HTMLElement): boolean {
  const rect = el.getBoundingClientRect();
  let parent: HTMLElement | null = el.parentElement;
  while (parent) {
    const style = window.getComputedStyle(parent);
    if (style.overflowY === 'auto' || style.overflowY === 'scroll') break;
    parent = parent.parentElement;
  }
  const top = parent ? parent.getBoundingClientRect().top : 0;
  const bottom = parent ? parent.getBoundingClientRect().bottom : window.innerHeight;
  return rect.top >= top && rect.bottom <= bottom;
}
