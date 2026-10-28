<template>
  <div class="agent-message">
    <!-- 执行过程时间线 -->
    <div v-if="processParts.length" class="process">
      <div class="process-head" @click="processOpen = !processOpen">
        <i :class="processOpen ? 'el-icon-caret-bottom' : 'el-icon-caret-right'"></i>
        <span class="process-title">{{ processTitle }}</span>
        <span class="process-meta">
          {{ thinkCount }} 段思考<template v-if="toolCount"> · {{ toolCount }} 个工具</template>
          <template v-if="elapsed"> · {{ elapsed }}</template>
        </span>
      </div>
      <el-collapse-transition>
        <div v-show="processOpen" class="process-body">
          <template v-for="(part, idx) in processParts">
            <ThinkBlock v-if="part.type === 'think'" :key="'t' + idx" :part="part" />
            <ToolCard v-else-if="part.type === 'tool_card'" :key="'c' + idx" :part="part" />
          </template>
        </div>
      </el-collapse-transition>
    </div>

    <!-- 最终回答 -->
    <div v-if="message.content" class="answer">
      <MarkdownView
        :content="message.content"
        :streaming="message.status === 'running'"
        mode="typewriter-blur"
        @render-complete="$emit('render-complete', $event)"
      />
    </div>

    <!-- 运行中占位 -->
    <div v-if="message.status === 'running' && !message.content" class="typing">
      <span></span><span></span><span></span>
    </div>

    <!-- 错误 -->
    <div v-if="message.error" class="error-box">
      <i class="el-icon-warning-outline"></i>
      <span>{{ message.error }}</span>
    </div>
  </div>
</template>

<script>
import MarkdownView from '../../components/MarkdownView.vue'
import { formatElapsedDuration } from '../../utils/formatDuration'
import ThinkBlock from './ThinkBlock.vue'
import ToolCard from './ToolCard.vue'

export default {
  name: 'DebugMessage',
  components: { MarkdownView, ThinkBlock, ToolCard },
  props: {
    message: { type: Object, required: true }
  },
  data() {
    return {
      processOpen: this.message.status === 'running'
    }
  },
  computed: {
    processParts() {
      return (this.message.parts || []).filter((p) => p.type !== 'text')
    },
    thinkCount() {
      return this.processParts.filter((p) => p.type === 'think').length
    },
    toolCount() {
      return this.processParts.filter((p) => p.type === 'tool_card').length
    },
    processTitle() {
      return this.message.status === 'running' ? '执行中...' : '执行过程'
    },
    elapsed() {
      return formatElapsedDuration(this.message.elapsedMs)
    }
  },
  watch: {
    'message.status'(val) {
      // 运行中自动展开，完成后自动收拢为一行摘要
      this.processOpen = val === 'running'
    }
  }
}
</script>

<style scoped>
.process {
  max-width: 680px;
  border: 0;
  border-radius: 0;
  background: transparent;
  margin-bottom: 10px;
  overflow: hidden;
}
.process-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 0;
  cursor: pointer;
  user-select: none;
  font-size: 12.5px;
  color: #5a6472;
}
.process-head:hover { background: transparent; }
.process-title { font-weight: 600; color: #3b6fb6; }
.process-meta { color: #98a2b3; font-size: 11.5px; margin-left: auto; }
.process-body { padding: 4px 0 10px; }

.answer { margin: 2px 0 8px; padding-left: 0; }

.typing { display: flex; min-height: 32px; align-items: center; gap: 5px; padding: 0; }
.typing span {
  width: 7px; height: 7px; border-radius: 50%; background: #c4cdd9;
  animation: blink 1.2s infinite ease-in-out;
}
.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.3; } 40% { opacity: 1; } }

.error-box {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  background: #fff5f5;
  border: 1px solid #ffd7d7;
  color: #d64545;
  border-radius: 8px;
  font-size: 12.5px;
  margin-bottom: 8px;
}
</style>
