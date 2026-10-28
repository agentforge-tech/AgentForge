<template>
  <div class="agent-message-content">
    <component :is="modeComponent" :message="message" @render-complete="onRenderComplete" />
    <div v-if="showActions" class="msg-actions agent-debug-message-actions">
      <button class="mini-btn agent-debug-mini-btn" type="button" title="复制回答" :disabled="!message.content" @click="copy">
        <i class="el-icon-document-copy"></i>
      </button>
      <span v-if="message.status === 'error'" class="status-tag error agent-debug-status-error">失败</span>
    </div>
  </div>
</template>

<script>
import { getRenderMode } from '../modes'

export default {
  name: 'AgentMessageContent',
  props: {
    message: { type: Object, required: true },
    mode: { type: String, required: true }
  },
  data() {
    return { renderedContent: null }
  },
  computed: {
    modeComponent() { return getRenderMode(this.mode).component },
    showActions() {
      if (this.message.status === 'running') return false
      return !this.message.content || this.renderedContent === this.message.content
    }
  },
  watch: {
    'message.status'(value) {
      if (value === 'running') this.renderedContent = null
    }
  },
  methods: {
    onRenderComplete(event) {
      if (event && event.content === this.message.content) this.renderedContent = event.content
    },
    async copy() {
      if (!this.message.content) return
      try {
        await navigator.clipboard.writeText(this.message.content)
        this.$message.success('已复制')
      } catch (_error) {
        this.$message.warning('复制失败，请手动选择文本')
      }
    }
  }
}
</script>

<style scoped>
.agent-message-content { min-width: 0; }
</style>
