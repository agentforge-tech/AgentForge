<template>
  <div class="trace-axis" :style="{ width: `${width}px` }">
    <template v-for="tick in ticks">
      <i :key="`${tick.offset}-line`" :style="{ left: `${tick.left}px` }"></i>
      <span :key="`${tick.offset}-label`" :style="{ left: `${tick.left}px` }">{{ format(tick.offset) }}</span>
    </template>
  </div>
</template>

<script>
export default {
  name: 'AgentTraceTimelineAxis',
  props: { width: { type: Number, required: true }, durationMs: { type: Number, default: 0 } },
  computed: {
    ticks() {
      const count = Math.max(2, Math.floor(this.width / 110)); const duration = Math.max(1, this.durationMs); const step = duration / count
      return Array.from({ length: count + 1 }, (_, index) => ({ offset: Math.round(step * index), left: (this.width / count) * index }))
    }
  },
  methods: { format(ms) { return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(ms >= 10000 ? 1 : 2)}s` } }
}
</script>

<style lang="scss" scoped>
@use '../../styles/admin/tokens' as *;
.trace-axis { position: relative; height: 30px; border-bottom: 1px solid $color-border; color: $color-text-muted; i { position: absolute; top: 21px; bottom: -500px; width: 1px; background: rgba(152,162,179,.14); pointer-events: none; } span { position: absolute; top: 4px; transform: translateX(-50%); font-size: 10px; white-space: nowrap; &:first-of-type { transform: none; } } }
</style>
