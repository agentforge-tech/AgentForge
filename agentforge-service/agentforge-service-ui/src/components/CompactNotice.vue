<template>
  <div class="compact-notice" :class="'compact-notice--' + part.status">
    <i :class="icon"></i>
    <span class="compact-notice__title">{{ title }}</span>
    <small v-if="detail" class="compact-notice__detail">{{ detail }}</small>
  </div>
</template>

<script>
export default {
  name: 'CompactNotice',
  props: {
    part: { type: Object, required: true },
    // 调试模式展示触发原因、阈值和压缩前后数据；发布模式只保留状态文案。
    detailed: { type: Boolean, default: false }
  },
  computed: {
    icon() {
      if (this.part.status === 'compact_failed') return 'el-icon-warning-outline'
      if (this.part.status === 'compacted') return 'el-icon-circle-check'
      if (this.part.finished) return 'el-icon-time'
      return 'el-icon-loading'
    },
    title() {
      if (!this.detailed) {
        if (this.part.status === 'compact_failed') return '上下文压缩失败'
        if (this.part.status === 'compacted') return '上下文阈值压缩完成'
        return '正在压缩上下文...'
      }
      if (this.part.status === 'compact_failed') return '上下文阈值压缩失败'
      if (this.part.status === 'compacted') return '上下文阈值压缩完成'
      if (this.part.finished) return '压缩前上下文'
      const before = this.formatTokens(this.part.beforeTokens)
      if (before) return `当前上下文大小 ${before}，已触发压缩阈值，正在压缩...`
      return '已触发上下文压缩，正在压缩...'
    },
    detail() {
      if (!this.detailed) {
        if (this.part.status === 'compacted') {
          const duration = Number(this.part.duration)
          return Number.isFinite(duration) && duration >= 0 ? `耗时 ${duration.toLocaleString()} ms` : ''
        }
        if (this.part.status === 'compact_failed') return this.part.errorMessage || ''
        return ''
      }
      const before = Number(this.part.beforeTokens)
      const after = Number(this.part.afterTokens)
      const max = Number(this.part.maxContextTokens)
      if (this.part.status === 'compacted' && this.isValidTokens(before) && this.isValidTokens(after)) {
        const messages = this.messageSummary()
        const duration = Number(this.part.duration)
        const elapsed = Number.isFinite(duration) && duration >= 0 ? `耗时 ${duration.toLocaleString()} ms` : ''
        const saved = this.savedTokens(before, after)
        const usage = this.usageSummary(max)
        return [
          `上下文 ${this.formatNumber(before)} → ${this.formatNumber(after)} tokens`,
          saved,
          usage,
          messages,
          elapsed
        ].filter(Boolean).join(' · ')
      }
      if (this.part.status === 'compact_failed') {
        const current = this.isValidTokens(before) ? `当前 ${this.formatTokens(before)}` : ''
        const limit = this.isValidTokens(max) ? `上下文上限 ${this.formatTokens(max)}` : ''
        const metrics = [current, limit].filter(Boolean).join(' · ')
        return [metrics, this.part.errorMessage].filter(Boolean).join(' · ')
      }
      const step = Number(this.part.currentStep)
      const trigger = this.triggerLabel(this.part.trigger)
      const context = this.isValidTokens(before) && this.isValidTokens(max)
        ? `当前 ${this.formatTokens(before)} / ${this.formatTokens(max)}`
        : (this.isValidTokens(before) ? `当前 ${this.formatTokens(before)}` : '')
      const usage = this.isValidTokens(this.part.beforeUsagePercent) ? `占比 ${this.formatPercent(this.part.beforeUsagePercent)}` : ''
      const stepText = Number.isFinite(step) && step >= 0 ? `第 ${step} 步` : ''
      return [context, usage, trigger, stepText].filter(Boolean).join(' · ')
    }
  },
  methods: {
    isValidTokens(value) {
      return Number.isFinite(value) && value >= 0
    },
    formatTokens(value) {
      const number = Number(value)
      return this.isValidTokens(number) ? `${this.formatNumber(number)} tokens` : ''
    },
    formatNumber(value) {
      const number = Number(value)
      if (!this.isValidTokens(number)) return ''
      if (number >= 1000000) return `${this.trimUnit(number / 1000000)}M`
      if (number >= 1000) return `${this.trimUnit(number / 1000)}K`
      return String(Math.round(number))
    },
    trimUnit(value) {
      const text = value.toFixed(1)
      return text.endsWith('.0') ? text.slice(0, -2) : text
    },
    formatPercent(value) {
      const number = Number(value)
      return Number.isFinite(number) ? `${number.toFixed(1)}%` : ''
    },
    savedTokens(before, after) {
      const diff = Number(this.part.savedTokens)
      const saved = Number.isFinite(diff) && diff >= 0 ? diff : before - after
      if (saved <= 0) return ''
      const percent = Number(this.part.savedPercent)
      const ratio = Number.isFinite(percent) ? percent : (saved / before) * 100
      return `节省 ${this.formatTokens(saved)} (${this.formatPercent(ratio)})`
    },
    usageSummary(max) {
      const beforeUsage = Number(this.part.beforeUsagePercent)
      const afterUsage = Number(this.part.afterUsagePercent)
      if (this.isValidTokens(beforeUsage) && this.isValidTokens(afterUsage)) {
        return `占比 ${this.formatPercent(beforeUsage)} → ${this.formatPercent(afterUsage)}`
      }
      return this.isValidTokens(max) ? `上下文上限 ${this.formatTokens(max)}` : ''
    },
    triggerLabel(trigger) {
      const labels = {
        model_call: '模型调用前触发',
        tool_result: '工具结果触发',
        concurrent_tool_result: '并发工具结果触发'
      }
      return labels[trigger] || ''
    },
    messageSummary() {
      const before = Number(this.part.beforeMessages)
      const after = Number(this.part.afterMessages)
      if (!Number.isFinite(before) || !Number.isFinite(after) || before < 0 || after < 0) return ''
      return ` · 消息 ${before.toLocaleString()} → ${after.toLocaleString()} 条`
    }
  }
}
</script>

<style scoped>
.compact-notice { display: flex; align-items: center; gap: 6px; padding: 7px 10px; margin: 6px 0; border-radius: 7px; background: #f2f7ff; color: #52677f; font-size: 12px; }
.compact-notice__title { min-width: 0; line-height: 1.5; }
.compact-notice__detail { margin-left: auto; color: #8495a8; text-align: right; line-height: 1.5; }
.compact-notice--compacted { background: #f0f9eb; color: #4f7d38; }
.compact-notice--compact_failed { background: #fef0f0; color: #b65353; }
</style>
