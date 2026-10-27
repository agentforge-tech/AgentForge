<template>
  <div class="agent-message-content">
    <component :is="modeComponent" :message="message" />
    <div v-if="message.status !== 'running'" class="msg-actions agent-debug-message-actions">
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
  computed: {
    modeComponent() { return getRenderMode(this.mode).component }
  },
  methods: {
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
