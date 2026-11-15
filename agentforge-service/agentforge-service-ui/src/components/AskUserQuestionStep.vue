<template>
  <div class="interrupt-step" :class="{ 'is-compact-step': compact }">
    <div class="interrupt-question__lead">
      <label>{{ question.title }}<em v-if="question.required !== false">*</em></label>
      <span v-if="meta.label" class="interrupt-question__type" :class="'is-' + meta.tone">{{ meta.label }}</span>
    </div>
    <small v-if="question.description">{{ question.description }}</small>
    <small v-if="meta.hint" class="interrupt-question__hint">{{ meta.hint }}</small>

    <el-radio-group v-if="question.type === 'SINGLE_CHOICE'" :value="value.single" @input="selectSingle">
      <el-radio v-for="option in question.options || []" :key="option.id" :label="option.id">{{ option.label }}</el-radio>
    </el-radio-group>
    <el-checkbox-group v-else-if="question.type === 'MULTIPLE_CHOICE'" :value="value.multiple" @input="selectMultiple">
      <el-checkbox v-for="option in question.options || []" :key="option.id" :label="option.id">{{ option.label }}</el-checkbox>
    </el-checkbox-group>
    <el-input
      v-else
      :value="value.text"
      type="textarea"
      :rows="3"
      :maxlength="question.maxLength || 4000"
      :placeholder="question.placeholder || '请输入'"
      @input="updateText"
    />

    <div v-if="question.type !== 'TEXT'" class="ask-user-custom">
      <span>{{ question.type === 'SINGLE_CHOICE' ? '自定义回答' : '补充回答' }}</span>
      <el-input
        :value="value.custom"
        :maxlength="question.maxLength || 4000"
        :placeholder="question.type === 'SINGLE_CHOICE' ? '请输入自定义回答' : '可选，请输入补充回答'"
        @input="updateCustom"
      />
    </div>
  </div>
</template>

<script>
export default {
  name: 'AskUserQuestionStep',
  props: {
    question: { type: Object, required: true },
    value: { type: Object, required: true },
    compact: { type: Boolean, default: false }
  },
  computed: {
    meta() {
      const question = this.question
      if (question.type === 'SINGLE_CHOICE') return { label: '单选', tone: 'single', hint: '以下选项仅可选择一项' }
      if (question.type === 'MULTIPLE_CHOICE') {
        const min = this.bound(question.minSelections)
        const max = this.bound(question.maxSelections)
        let hint = '以下选项可多选'
        if (min !== null && max !== null) hint = min === max ? `请选择 ${min} 项` : `请选择 ${min} - ${max} 项`
        else if (min !== null) hint = `可多选，至少 ${min} 项`
        else if (max !== null) hint = `可多选，最多 ${max} 项`
        return { label: '多选', tone: 'multiple', hint }
      }
      return { label: '填空', tone: 'text', hint: '' }
    }
  },
  methods: {
    bound(value) {
      if (value === null || value === undefined || value === '') return null
      const parsed = Number(value)
      return Number.isFinite(parsed) ? parsed : null
    },
    selectSingle(optionId) {
      this.$emit('update', { field: 'single', value: optionId })
      if (optionId) this.$emit('update', { field: 'custom', value: '' })
    },
    selectMultiple(optionIds) {
      this.$emit('update', { field: 'multiple', value: optionIds || [] })
    },
    updateText(text) {
      this.$emit('update', { field: 'text', value: text })
    },
    updateCustom(custom) {
      this.$emit('update', { field: 'custom', value: custom })
      if (String(custom || '').trim()) this.$emit('update', { field: 'single', value: '' })
    }
  }
}
</script>

<style scoped>
.interrupt-step { display: flex; flex-direction: column; gap: 6px; }
.interrupt-question__lead { display: flex; align-items: flex-start; gap: 8px; }
.interrupt-question__lead > label { flex: 1; min-width: 0; color: #303133; font-size: 14px; font-weight: 600; line-height: 1.45; }
.interrupt-step em { color: #d85a45; font-style: normal; margin-left: 3px; }
.interrupt-question__type { flex: 0 0 auto; margin-top: 2px; padding: 0 6px; border: 1px solid #ecd9a8; border-radius: 999px; background: #fff6df; color: #8a6414; font-size: 10px; font-weight: 600; line-height: 15px; letter-spacing: .3px; }
.interrupt-question__type.is-multiple { border-color: #bcd9f5; background: #eef7ff; color: #2468a8; }
.interrupt-question__type.is-text { border-color: #dfe3ea; background: #f4f6f9; color: #5a6472; }
.interrupt-step > small { color: #7c6b45; font-size: 11.5px; line-height: 1.45; }
.interrupt-question__hint { color: #a08a5c; }
.interrupt-step .el-radio-group,
.interrupt-step .el-checkbox-group { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 10px; width: 100%; }
.interrupt-step .el-radio,
.interrupt-step .el-checkbox { display: flex; align-items: center; box-sizing: border-box; min-width: 0; min-height: 32px; margin: 0; padding: 5px 9px; border: 1px solid #ece3cf; border-radius: 7px; background: #fff; white-space: normal; font-size: 12.5px; line-height: 17px; transition: border-color .16s ease, background .16s ease, box-shadow .16s ease; }
.interrupt-step .el-radio:hover,
.interrupt-step .el-checkbox:hover { border-color: #d9b45e; background: #fffaf0; }
.interrupt-step .el-radio.is-checked,
.interrupt-step .el-checkbox.is-checked { border-color: #8bc0f3; background: #f2f8ff; box-shadow: inset 0 0 0 1px rgba(64, 158, 255, .18); }
.interrupt-step ::v-deep .el-radio__label,
.interrupt-step ::v-deep .el-checkbox__label { padding-left: 7px; font-size: 12.5px; line-height: 17px; }
.interrupt-step ::v-deep .el-radio.is-checked .el-radio__label,
.interrupt-step ::v-deep .el-checkbox.is-checked .el-checkbox__label { color: #2468a8; font-weight: 500; }
.ask-user-custom { display: flex; align-items: center; gap: 8px; margin-top: 1px; }
.ask-user-custom > span { flex: 0 0 auto; min-width: 60px; color: #786536; font-size: 12px; font-weight: 500; }
.ask-user-custom .el-input { flex: 1; }
.ask-user-custom ::v-deep .el-input__inner { height: 32px; line-height: 32px; font-size: 12.5px; }

.is-compact-step { gap: 7px; }
.is-compact-step .interrupt-question__lead > label { font-size: 14px; }
.is-compact-step .interrupt-question__type { margin-top: 3px; line-height: 15px; }
.is-compact-step > small { font-size: 11px; }
.is-compact-step .el-radio,
.is-compact-step .el-checkbox { padding: 5px 8px; font-size: 12px; }
.is-compact-step ::v-deep .el-radio__label,
.is-compact-step ::v-deep .el-checkbox__label { font-size: 12px; }
.is-compact-step .ask-user-custom ::v-deep .el-input__inner { height: 30px; line-height: 30px; font-size: 12px; }

@media (max-width: 520px) {
  .interrupt-step .el-radio-group,
  .interrupt-step .el-checkbox-group { grid-template-columns: 1fr; }
}
</style>