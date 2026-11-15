<template>
  <section class="interrupt-card" :class="['is-' + status.toLowerCase(), { 'is-compact': compact }]">
    <header :class="{ 'is-collapsible': status === 'PENDING' }" @click="toggleCollapsed">
      <span class="interrupt-card__icon"><i class="el-icon-user"></i></span>
      <div><b>{{ title }}</b><small>{{ collapsed ? '已收起，点击展开确认' : subtitle }}</small></div>
      <button
        v-if="status === 'PENDING'"
        class="interrupt-card__toggle"
        type="button"
        :aria-expanded="!collapsed"
        :title="collapsed ? '展开确认' : '收起确认'"
        @click.stop="toggleCollapsed"
      >
        <i :class="collapsed ? 'el-icon-arrow-up' : 'el-icon-arrow-down'"></i>
      </button>
    </header>

    <el-collapse-transition>
    <div v-show="!collapsed" v-if="status === 'PENDING'" class="interrupt-card__body">
      <div v-if="currentStep" class="ask-user-stage">
        <div class="ask-user-progress">
          <span>{{ hasPermissionStep ? '确认' : '问题' }} {{ currentIndex + 1 }} / {{ steps.length }}</span>
          <div class="ask-user-dots">
            <button
              v-for="(step, index) in steps"
              :key="step.question ? step.item.interruptId + '-' + step.question.id : step.item.interruptId + '-p'"
              type="button"
              :class="{ active: index === currentIndex, answered: isAnswered(step) }"
              :aria-label="'切换到第 ' + (index + 1) + ' 项'"
              @click="goTo(index)"
            ></button>
          </div>
        </div>
        <div class="interrupt-question">
          <AskUserQuestionStep
            v-if="currentStep.question"
            :question="currentStep.question"
            :value="currentValue"
            :compact="compact"
            @update="updateAnswer"
          />
          <PermissionApprovalStep
            v-else
            :item="currentStep.item"
            :action="actions[currentStep.item.interruptId]"
            :compact="compact"
            @update="updateAction"
          />
        </div>
        <div class="ask-user-navigation">
          <el-button size="small" :disabled="currentIndex === 0" icon="el-icon-arrow-left" @click="previous">上一项</el-button>
          <el-button v-if="currentIndex < steps.length - 1" type="primary" size="small" @click="next">下一项<i class="el-icon-arrow-right el-icon--right"></i></el-button>
        </div>
      </div>

      <div v-if="localError" class="interrupt-error">{{ localError }}</div>
      <el-button v-if="canSubmit" class="interrupt-submit" type="primary" size="small" :loading="submitting" @click="submit">
        {{ submitLabel }}
      </el-button>
    </div>
    </el-collapse-transition>
    <div v-if="status !== 'PENDING' && part.error" class="interrupt-error">{{ part.error }}</div>
  </section>
</template>

<script>
import { resumeInterrupt } from '../utils/chatTransport'
import AskUserQuestionStep from './AskUserQuestionStep.vue'
import PermissionApprovalStep from './PermissionApprovalStep.vue'

export default {
  name: 'InterruptCard',
  components: { AskUserQuestionStep, PermissionApprovalStep },
  props: {
    part: { type: Object, required: true },
    compact: { type: Boolean, default: false }
  },
  data() { return { answers: {}, actions: {}, currentIndex: 0, submitting: false, localError: '', collapsed: false } },
  computed: {
    status() { return this.part.status || 'PENDING' },
    title() {
      if (this.status === 'PENDING') return '需要您确认'
      if (this.status === 'RESUMING') return '正在继续执行'
      if (this.status === 'COMPLETED') return '已完成确认'
      if (this.status === 'CANCELLED') return '已取消'
      return '恢复失败'
    },
    subtitle() { return this.status === 'PENDING' ? '作答后 Agent 将从当前位置继续' : this.status },
    steps() {
      const steps = []
      ;(this.part.interrupts || []).forEach((item) => {
        if (item.type === 'ASK_USER') {
          this.questions(item).forEach((question) => steps.push({ item, question }))
        } else {
          steps.push({ item, permission: true })
        }
      })
      return steps
    },
    hasPermissionStep() { return this.steps.some((step) => step.permission) },
    currentStep() { return this.steps[this.currentIndex] || null },
    currentValue() {
      if (!this.currentStep?.question) return { single: '', multiple: [], text: '', custom: '' }
      return this.answers[this.currentStep.item.interruptId]?.[this.currentStep.question.id]
        || { single: '', multiple: [], text: '', custom: '' }
    },
    canSubmit() { return !this.steps.length || this.currentIndex === this.steps.length - 1 },
    submitLabel() {
      const ask = (this.part.interrupts || []).find((item) => item.type === 'ASK_USER')
      return ask?.payload?.submitLabel || '确认并继续'
    }
  },
  created() { this.initialize() },
  watch: {
    'part.batchId'(value, previous) {
      if (value !== previous) this.collapsed = false
    }
  },
  methods: {
    toggleCollapsed() {
      if (this.status !== 'PENDING') return
      this.collapsed = !this.collapsed
    },
    questions(item) { return item?.payload?.questions || [] },
    selectionBound(value) {
      if (value === null || value === undefined || value === '') return null
      const parsed = Number(value)
      return Number.isFinite(parsed) ? parsed : null
    },
    isAnswered(step) {
      if (step.permission) return Boolean(this.actions[step.item.interruptId])
      const value = this.answers[step.item.interruptId]?.[step.question.id]
      if (!value) return false
      if (step.question.type === 'TEXT') return Boolean(value.text && value.text.trim())
      return Boolean(value.single || value.multiple.length || (value.custom && value.custom.trim()))
    },
    validateStep(step) {
      if (!step) return true
      const index = this.steps.indexOf(step) + 1
      if (step.permission) {
        if (!this.actions[step.item.interruptId]) {
          this.localError = `请先处理第 ${index} 项审批`
          return false
        }
        return true
      }
      const value = this.answers[step.item.interruptId]?.[step.question.id]
      if (step.question.required !== false && !this.isAnswered(step)) {
        this.localError = `请先完成第 ${index} 题`
        return false
      }
      if (step.question.type === 'SINGLE_CHOICE' && value?.single && String(value.custom || '').trim()) {
        this.localError = `第 ${index} 题的预设选项与自定义内容只能选一种`
        return false
      }
      if (step.question.type === 'MULTIPLE_CHOICE') {
        const count = (value?.multiple || []).length + (String(value?.custom || '').trim() ? 1 : 0)
        const hasMin = step.question.minSelections !== null && step.question.minSelections !== undefined
        const hasMax = step.question.maxSelections !== null && step.question.maxSelections !== undefined
        const min = hasMin && Number.isFinite(Number(step.question.minSelections)) ? Number(step.question.minSelections) : 0
        const max = hasMax && Number.isFinite(Number(step.question.maxSelections)) ? Number(step.question.maxSelections) : 20
        if (count < min || count > max) {
          this.localError = `第 ${index} 题需要选择 ${min} - ${max} 项`
          return false
        }
      }
      return true
    },
    goTo(index) { this.localError = ''; this.currentIndex = index },
    previous() { if (this.currentIndex > 0) this.goTo(this.currentIndex - 1) },
    next() {
      if (!this.validateStep(this.currentStep)) return
      if (this.currentIndex < this.steps.length - 1) this.goTo(this.currentIndex + 1)
    },
    updateAnswer({ field, value }) {
      this.$set(this.currentValue, field, value)
      this.localError = ''
    },
    updateAction(action) {
      if (!this.currentStep?.item) return
      this.$set(this.actions, this.currentStep.item.interruptId, action)
      this.localError = ''
    },
    initialize() {
      ;(this.part.interrupts || []).forEach((item) => {
        if (item.type === 'ASK_USER') {
          const values = {}
          this.questions(item).forEach((question) => { values[question.id] = { single: '', multiple: [], text: '', custom: '' } })
          this.$set(this.answers, item.interruptId, values)
        } else this.$set(this.actions, item.interruptId, 'APPROVE_ONCE')
      })
    },
    buildResponses() {
      return (this.part.interrupts || []).map((item) => {
        if (item.type !== 'ASK_USER') return { interruptId: item.interruptId, action: this.actions[item.interruptId] }
        const values = this.answers[item.interruptId] || {}
        const answers = this.questions(item).map((question) => {
          const value = values[question.id] || {}
          if (question.type === 'SINGLE_CHOICE') return { questionId: question.id, selectedOptionIds: value.single ? [value.single] : [], customValue: value.custom || null }
          if (question.type === 'MULTIPLE_CHOICE') return { questionId: question.id, selectedOptionIds: value.multiple || [], customValue: value.custom || null }
          return { questionId: question.id, customValue: value.text || '' }
        })
        return { interruptId: item.interruptId, answers }
      })
    },
    buildApprovalSnapshots() {
      return (this.part.interrupts || [])
        .filter((item) => item.type !== 'ASK_USER')
        .map((item) => ({ interruptId: item.interruptId, action: this.actions[item.interruptId] || '' }))
    },
    buildAnswerSnapshots() {
      return (this.part.interrupts || [])
        .filter((item) => item.type === 'ASK_USER')
        .map((item) => {
          const values = this.answers[item.interruptId] || {}
          const answers = this.questions(item).map((question) => {
            const value = values[question.id] || {}
            const selectedOptionIds =
              question.type === 'SINGLE_CHOICE'
                ? (value.single ? [value.single] : [])
                : question.type === 'MULTIPLE_CHOICE'
                  ? (value.multiple || [])
                  : []
            const customValue =
              question.type === 'TEXT' ? (value.text || '') : (value.custom || '')
            return {
              questionId: question.id,
              selectedOptionIds,
              customValue: String(customValue).trim() || null
            }
          })
          return { interruptId: item.interruptId, answers }
        })
    },
    async submit() {
      const invalidIndex = this.steps.findIndex((step) => !this.validateStep(step))
      if (invalidIndex >= 0) { this.currentIndex = invalidIndex; return }
      this.submitting = true
      this.localError = ''
      try {
        await resumeInterrupt(this.part.batchId, this.buildResponses())
        this.$set(this.part, 'answers', this.buildAnswerSnapshots())
        this.$set(this.part, 'approvalActions', this.buildApprovalSnapshots())
        this.part.status = 'RESUMING'
        this.$emit('resumed', {
          batchId: this.part.batchId,
          messageId: this.part.messageId,
          afterSeq: Number(this.part.eventSeq || 0)
        })
      } catch (error) {
        this.localError = error.message || '提交失败'
      } finally { this.submitting = false }
    }
  }
}
</script>

<style scoped>
.interrupt-card { position: relative; width: 100%; box-sizing: border-box; margin: 0 0 10px; border: 1px solid #e8c46a; border-radius: 14px; background: #fffaf0; padding: 14px; box-shadow: 0 8px 24px rgba(86, 65, 21, .08); }
.interrupt-card header { display: flex; align-items: center; gap: 10px; }
.interrupt-card header.is-collapsible { cursor: pointer; user-select: none; padding-right: 26px; }
.interrupt-card__toggle { position: absolute; z-index: 2; top: 9px; right: 9px; display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: #9a8a68; cursor: pointer; transition: background .15s ease, color .15s ease; }
.interrupt-card__toggle:hover { background: #f8efdb; color: #7a5a12; }
.interrupt-card__toggle i { font-size: 11px; transition: transform .2s; }

.interrupt-card header div { display: flex; flex-direction: column; gap: 2px; }
.interrupt-card header small, .interrupt-question small { color: #7c6b45; }
.interrupt-card__icon { width: 30px; height: 30px; border-radius: 9px; background: #f4d98f; display: grid; place-items: center; }
.interrupt-card__body { margin-top: 14px; }
.ask-user-stage { min-height: 154px; }
.ask-user-progress { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; color: #786536; font-size: 12px; }
.ask-user-dots { display: flex; align-items: center; gap: 6px; }
.ask-user-dots button { width: 8px; height: 8px; border: 0; border-radius: 50%; padding: 0; background: #d8cdb5; cursor: pointer; transition: .18s ease; }
.ask-user-dots button.answered { background: #80ad8c; }
.ask-user-dots button.active { width: 20px; border-radius: 6px; background: #d09b2f; }
.ask-user-navigation { display: flex; align-items: center; justify-content: space-between; margin-top: 4px; }
.interrupt-question { display: flex; flex-direction: column; gap: 7px; margin-bottom: 14px; }
.interrupt-error { margin: 8px 0; color: #c74735; }
.interrupt-submit { margin-top: 4px; float: right; }
.interrupt-card__body::after { display: block; clear: both; content: ''; }
.interrupt-card:not(.is-compact) { width: 100%; margin: 0 0 8px; padding: 12px 14px; border-radius: 12px; }
.interrupt-card:not(.is-compact) header { gap: 8px; }
.interrupt-card:not(.is-compact) header b { font-size: 14px; line-height: 20px; }
.interrupt-card:not(.is-compact) header small { font-size: 11.5px; line-height: 1.35; }
.interrupt-card:not(.is-compact) .interrupt-card__toggle { top: 10px; right: 10px; }
.interrupt-card:not(.is-compact) .interrupt-card__icon { width: 26px; height: 26px; flex: 0 0 26px; border-radius: 8px; font-size: 12px; }
.interrupt-card:not(.is-compact) .interrupt-card__body { margin-top: 10px; }
.interrupt-card:not(.is-compact) .ask-user-stage { min-height: 0; }
.interrupt-card:not(.is-compact) .ask-user-progress { margin-bottom: 8px; font-size: 11.5px; }
.interrupt-card:not(.is-compact) .ask-user-dots button { width: 7px; height: 7px; }
.interrupt-card:not(.is-compact) .ask-user-dots button.active { width: 18px; }
.interrupt-card:not(.is-compact) .interrupt-question { gap: 6px; margin-bottom: 10px; }
.interrupt-card:not(.is-compact) .interrupt-card:not(.is-compact) .interrupt-card:not(.is-compact) .interrupt-card:not(.is-compact) .ask-user-navigation { margin-top: 0; padding-top: 0; }
.interrupt-card:not(.is-compact) .ask-user-navigation .el-button { padding: 6px 10px; font-size: 12px; }
.interrupt-card:not(.is-compact) .interrupt-error { margin: 6px 0 0; font-size: 11.5px; }
.is-compact { margin: 0 28px 8px; width: calc(100% - 56px); padding: 10px 12px; border-radius: 11px; }
.is-compact header { gap: 7px; }
.is-compact header b { font-size: 13px; }
.is-compact header small { font-size: 11px; line-height: 1.2; }
.is-compact .interrupt-card__toggle { top: 7px; right: 7px; }
.is-compact .interrupt-card__icon { width: 25px; height: 25px; flex: 0 0 25px; border-radius: 7px; font-size: 12px; }
.is-compact .interrupt-card__body { margin-top: 8px; }
.is-compact .ask-user-stage { min-height: 0; }
.is-compact .ask-user-progress { margin-bottom: 7px; font-size: 11px; }
.is-compact .ask-user-dots { gap: 4px; }
.is-compact .ask-user-dots button { width: 6px; height: 6px; }
.is-compact .ask-user-dots button.active { width: 16px; }
.is-compact .interrupt-question { gap: 7px; margin-bottom: 8px; }
.is-compact .is-compact .is-compact @media (max-width: 520px) {
  .is-compact   .is-compact .interrupt-question .el-checkbox-group { grid-template-columns: 1fr; }
}
.is-compact .ask-user-navigation { padding-top: 0; }
.is-compact .ask-user-navigation .el-button { padding: 6px 10px; font-size: 11px; }
.is-compact .interrupt-error { margin: 5px 0 0; font-size: 11px; }
.is-completed, .is-resuming { border-color: #b8d9c2; background: #f4fbf6; }
.is-failed { border-color: #efb2aa; background: #fff5f3; }
</style>
