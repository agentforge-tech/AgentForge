<template>
  <div class="agent-retry-notice">
    <span class="retry-spinner" aria-hidden="true"></span>
    <span class="retry-word">Retrying</span>
    <span class="retry-notice-text">({{ text }})</span>
  </div>
</template>

<script>
export default {
  name: 'RetryNotice',
  props: { part: { type: Object, required: true } },
  data() {
    return { now: Date.now(), timer: null }
  },
  computed: {
    remainingSeconds() {
      return Math.max(0, Math.ceil((Number(this.part.deadline || 0) - this.now) / 1000))
    },
    progress() {
      return `${this.part.attempt || 1}/${this.part.maxRetries || 1}`
    },
    text() {
      const countdown = this.remainingSeconds > 0 ? `${this.remainingSeconds}s` : '即将重试'
      const step = this.part.currentStep ? `第 ${this.part.currentStep} 轮 · ` : ''
      return `${step}${this.progress} · ${countdown}`
    }
  },
  mounted() {
    this.timer = setInterval(() => {
      this.now = Date.now()
    }, 250)
  },
  beforeDestroy() {
    if (this.timer) clearInterval(this.timer)
  }
}
</script>

<style scoped>
.agent-retry-notice {
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 28px;
  align-items: center;
  gap: 7px;
  padding: 5px 0 7px;
  overflow: hidden;
  color: #707987;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  white-space: nowrap;
}
.retry-spinner {
  width: 10px;
  height: 10px;
  box-sizing: border-box;
  flex: 0 0 10px;
  border: 1.5px solid rgba(139, 163, 224, 0.22);
  border-top-color: #8ca4fa;
  border-radius: 50%;
  animation: retrySpin 0.8s linear infinite;
}
.retry-word {
  display: inline-block;
  flex: 0 0 auto;
  color: #66707e;
  font-weight: 500;
  white-space: nowrap;
  word-break: keep-all;
  animation: retryWordPulse 1.5s ease-in-out infinite;
}
.retry-notice-text {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: #59616e;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@keyframes retrySpin {
  to { transform: rotate(360deg); }
}
@keyframes retryWordPulse {
  0%, 100% { color: #66707e; opacity: 0.72; }
  50% { color: #aab8e8; opacity: 1; }
}
</style>
