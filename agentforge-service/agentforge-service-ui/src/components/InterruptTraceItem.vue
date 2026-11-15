<template>
  <div class="interrupt-trace" :class="['is-' + tone, 'mode-' + mode]">
    <div v-if="hasDetail" class="interrupt-trace-head" @click="open = !open">
      <i class="interrupt-trace-icon" :class="icon"></i>
      <span class="interrupt-trace-label">{{ label }}</span>
      <span v-if="summary" class="interrupt-trace-detail" :title="summary">{{ summary }}</span>
      <i class="el-icon-arrow-down interrupt-trace-arrow" :class="{ open }"></i>
    </div>
    <div v-else class="interrupt-trace-head">
      <i class="interrupt-trace-icon" :class="icon"></i>
      <span class="interrupt-trace-label">{{ label }}</span>
      <span v-if="summary" class="interrupt-trace-detail" :title="summary">{{ summary }}</span>
    </div>

    <el-collapse-transition>
      <div v-show="hasDetail && open" class="interrupt-trace-body">
        <div v-if="mode === 'debug' && part.batchId" class="interrupt-trace-meta">batchId: {{ part.batchId }}</div>
        <div v-for="item in items" :key="item.interruptId" class="interrupt-trace-item">
          <div v-if="mode === 'debug'" class="interrupt-trace-item-head">
            <span class="interrupt-trace-badge">{{ item.typeLabel }}</span>
            <span class="interrupt-trace-item-id">{{ item.interruptId }}</span>
          </div>
          <div v-for="(question, qIndex) in item.questions" :key="question.id" class="interrupt-trace-question">
            <div class="interrupt-trace-question-title">
              <span v-if="mode === 'debug'" class="interrupt-trace-index">{{ qIndex + 1 }}</span>
              <span>{{ question.title }}</span>
              <em v-if="question.typeLabel">{{ question.typeLabel }}</em>
            </div>
            <div class="interrupt-trace-answer" :class="{ empty: !question.answerText }">
              <template v-if="question.answerText">{{ question.answerText }}</template>
              <template v-else>未作答</template>
            </div>
            <div v-if="mode === 'debug' && question.optionIds.length" class="interrupt-trace-ids">
              选项 ID：{{ question.optionIds.join('、') }}
            </div>
          </div>
          <div v-if="item.isPermission" class="interrupt-trace-question">
            <div class="interrupt-trace-question-title">
              <span v-if="mode === 'debug'" class="interrupt-trace-index">审</span>
              <span>工具 <code class="interrupt-trace-code">{{ item.toolName }}</code></span>
            </div>
            <div class="interrupt-trace-answer" :class="{ empty: !item.actionLabel }">{{ item.actionText }}</div>
            <div v-if="item.arguments" class="interrupt-trace-args">
              参数：{{ mode === 'debug' ? truncate(item.arguments) : preview(item.arguments) }}
            </div>
          </div>
        </div>
      </div>
    </el-collapse-transition>

    <div v-if="error" class="interrupt-trace-error">{{ error }}</div>
  </div>
</template>

<script>
const TYPE_LABELS = { SINGLE_CHOICE: '单选', MULTIPLE_CHOICE: '多选', TEXT: '填空' }
const ACTION_LABELS = { APPROVE_ONCE: '允许', APPROVE_ALWAYS: '本次运行一直允许', REJECT: '拒绝' }

export default {
  name: 'InterruptTraceItem',
  props: {
    part: { type: Object, required: true },
    mode: { type: String, default: 'elegant' }
  },
  data() {
    return { open: this.mode === 'debug' }
  },
  computed: {
    status() { return this.part.status || 'PENDING' },
    tone() {
      if (this.status === 'RESUMING') return 'running'
      if (this.status === 'COMPLETED') return 'done'
      if (this.status === 'FAILED') return 'failed'
      if (this.status === 'CANCELLED') return 'cancelled'
      return 'pending'
    },
    icon() {
      if (this.status === 'RESUMING') return 'el-icon-loading'
      if (this.status === 'COMPLETED') return 'el-icon-circle-check'
      if (this.status === 'FAILED') return 'el-icon-warning-outline'
      if (this.status === 'CANCELLED') return 'el-icon-remove-outline'
      return 'el-icon-chat-dot-square'
    },
    label() {
      if (this.status === 'RESUMING') return '用户已确认，正在恢复执行'
      if (this.status === 'COMPLETED') return '用户已确认，已恢复执行'
      if (this.status === 'FAILED') return '恢复执行失败'
      if (this.status === 'CANCELLED') return '确认流程已取消'
      return '等待用户确认'
    },
    answerMap() {
      const map = {}
      ;(this.part.answers || []).forEach((entry) => {
        const values = {}
        ;(entry.answers || []).forEach((answer) => { values[answer.questionId] = answer })
        map[entry.interruptId] = values
      })
      return map
    },
    approvalMap() {
      const map = {}
      ;(this.part.approvalActions || []).forEach((entry) => { map[entry.interruptId] = entry.action })
      return map
    },
    items() {
      return (this.part.interrupts || []).map((interrupt) => {
        const answers = this.answerMap[interrupt.interruptId] || {}
        const questions = (interrupt.payload?.questions || []).map((question) =>
          this.toQuestionView(question, answers[question.id])
        )
        const action = this.approvalMap[interrupt.interruptId]
        const isPermission = interrupt.type !== 'ASK_USER'
        return {
          interruptId: interrupt.interruptId,
          typeLabel: interrupt.type === 'ASK_USER' ? 'ASK_USER' : 'PERMISSION_APPROVAL',
          isPermission,
          toolName: interrupt.toolRequest?.name || '',
          arguments: interrupt.toolRequest?.arguments || '',
          questions,
          actionLabel: action ? (ACTION_LABELS[action] || action) : '',
          actionText: action
            ? (ACTION_LABELS[action] || action)
            : (this.status === 'COMPLETED' ? '已处理（历史未记录动作）' : (this.status === 'CANCELLED' ? '已取消' : '未处理'))
        }
      })
    },
    questionCount() { return this.items.reduce((total, item) => total + item.questions.length, 0) },
    approvalCount() { return this.items.filter((item) => item.isPermission).length },
    resolvedApprovalCount() { return this.items.filter((item) => item.isPermission && item.actionLabel).length },
    answeredCount() {
      return this.items.reduce(
        (total, item) => total + item.questions.filter((question) => Boolean(question.answerText)).length,
        0
      )
    },
    hasDetail() { return this.items.some((item) => item.questions.length || item.isPermission) },
    summary() {
      const parts = []
      if (this.questionCount) {
        parts.push(this.answeredCount ? `已答 ${this.answeredCount} / ${this.questionCount} 题` : `共 ${this.questionCount} 题`)
      }
      if (this.approvalCount) {
        parts.push(this.resolvedApprovalCount ? `已处理 ${this.resolvedApprovalCount} / ${this.approvalCount} 项审批` : `共 ${this.approvalCount} 项审批`)
      }
      return parts.join(' · ')
    },
    error() { return this.status === 'FAILED' ? (this.part.error || '') : '' }
  },
  methods: {
    truncate(value) {
      const text = typeof value === 'string' ? value : JSON.stringify(value)
      const limit = 480
      return text.length > limit ? `${text.slice(0, limit)}…` : text
    },
    preview(value) {
      const text = (typeof value === 'string' ? value : JSON.stringify(value)).replace(/\s+/g, ' ')
      return text.length > 120 ? `${text.slice(0, 120)}…` : text
    },
    toQuestionView(question, answer) {
      const selectedIds = answer?.selectedOptionIds || []
      const custom = String(answer?.customValue || '').trim()
      const labels = selectedIds.map((id) => {
        const option = (question.options || []).find((item) => item.id === id)
        return option ? option.label : id
      })
      let answerText = ''
      if (question.type === 'TEXT') answerText = custom
      else if (labels.length && custom) answerText = `${labels.join('、')}；自定义：${custom}`
      else answerText = labels.length ? labels.join('、') : (custom ? `自定义：${custom}` : '')
      if (this.mode === 'debug' && answerText) {
        const detailed = selectedIds.map((id, index) => `${labels[index]}（${id}）`).join('、')
        answerText = custom
          ? (detailed ? `${detailed}；自定义：${custom}` : `自定义：${custom}`)
          : detailed
      }
      return {
        id: question.id,
        title: question.title || question.id,
        typeLabel: this.mode === 'debug' ? (TYPE_LABELS[question.type] || question.type) : '',
        answerText,
        optionIds: selectedIds
      }
    }
  }
}
</script>

<style scoped>
.interrupt-trace { margin: 4px 0 6px; }
.interrupt-trace-head { display: flex; align-items: center; gap: 7px; max-width: 100%; font-size: 12.5px; }
.interrupt-trace.mode-debug .interrupt-trace-head { width: 100%; box-sizing: border-box; padding: 4px 0; cursor: pointer; user-select: none; }
.interrupt-trace:not(.mode-debug) .interrupt-trace-head { width: fit-content; cursor: pointer; user-select: none; }
.interrupt-trace-icon { flex: 0 0 auto; font-size: 13px; color: #91a0b6; }
.interrupt-trace-label { flex: 0 0 auto; color: #718096; }
.interrupt-trace-detail { min-width: 0; overflow: hidden; color: #98a2b3; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.interrupt-trace-arrow { flex: 0 0 auto; color: #b0b8c4; font-size: 11px; transition: transform .2s; }
.interrupt-trace-arrow.open { transform: rotate(180deg); }
.interrupt-trace.is-pending .interrupt-trace-icon,
.interrupt-trace.is-pending .interrupt-trace-label { color: #b08a2e; }
.interrupt-trace.is-done .interrupt-trace-icon { color: #6aa97f; }
.interrupt-trace.is-failed .interrupt-trace-icon,
.interrupt-trace.is-failed .interrupt-trace-label { color: #c74735; }
.interrupt-trace.is-cancelled .interrupt-trace-icon,
.interrupt-trace.is-cancelled .interrupt-trace-label { color: #8a94a6; }
.interrupt-trace-error { margin: 4px 0 0 20px; color: #c74735; font-size: 12px; }

.interrupt-trace-body { margin: 2px 0 0 20px; }
.interrupt-trace.mode-debug .interrupt-trace-body { margin: 2px 0 0; padding: 8px 10px; border-radius: 6px; background: #f7f9fc; }
.interrupt-trace-meta { margin-bottom: 6px; color: #98a2b3; font-size: 11px; font-family: Consolas, monospace; word-break: break-all; }
.interrupt-trace-item + .interrupt-trace-item { margin-top: 8px; padding-top: 8px; border-top: 1px dashed #e5e9f0; }
.interrupt-trace-item-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.interrupt-trace-badge { padding: 0 6px; border: 1px solid #dbe3ee; border-radius: 4px; background: #fff; color: #5b6b82; font-size: 10px; line-height: 16px; letter-spacing: .3px; }
.interrupt-trace-item-id { min-width: 0; overflow: hidden; color: #a3adbc; font-size: 10.5px; font-family: Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.interrupt-trace-question + .interrupt-trace-question { margin-top: 6px; }
.interrupt-trace-question-title { display: flex; align-items: baseline; gap: 5px; color: #5b6b82; font-size: 12px; line-height: 18px; }
.interrupt-trace-question-title em { color: #a3adbc; font-style: normal; font-size: 10.5px; }
.interrupt-trace-index { flex: 0 0 auto; width: 15px; height: 15px; border-radius: 50%; background: #e8edf5; color: #74839a; font-size: 10px; line-height: 15px; text-align: center; }
.interrupt-trace-answer { margin-left: 20px; color: #303133; font-size: 12.5px; line-height: 18px; word-break: break-word; }
.mode-debug .interrupt-trace-answer { margin-left: 20px; color: #2f3a4a; }
.interrupt-trace-answer.empty { color: #b0b8c4; }
.interrupt-trace-ids { margin: 2px 0 0 20px; color: #a3adbc; font-size: 10.5px; font-family: Consolas, monospace; word-break: break-all; }
.interrupt-trace-args { margin: 2px 0 0 20px; color: #9a8a68; font-size: 10.5px; font-family: Consolas, monospace; line-height: 1.5; word-break: break-all; }
.interrupt-trace-code { padding: 0 4px; border-radius: 4px; background: #eef2f8; color: #4a5b73; font-family: Consolas, monospace; font-size: 11px; }
.mode-elegant .interrupt-trace-answer { margin-left: 0; }
</style>