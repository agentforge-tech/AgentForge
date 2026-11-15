<template>
  <div class="interrupt-step" :class="{ 'is-compact-step': compact }">
    <div class="interrupt-question__lead">
      <label>请确认是否允许执行 <code>{{ toolName }}</code><em>*</em></label>
      <span class="interrupt-question__type is-approval">审批</span>
    </div>
    <small class="interrupt-question__hint">允许后 Agent 将按所选策略执行该工具</small>

    <div class="approval-args">
      <div class="approval-args-head" @click="argsOpen = !argsOpen">
        <span class="approval-args-title">调用参数</span>
        <span class="approval-args-preview">{{ argumentPreview }}</span>
        <i class="el-icon-arrow-down approval-args-arrow" :class="{ open: argsOpen }"></i>
      </div>
      <el-collapse-transition>
        <pre v-show="argsOpen" class="approval-args-body">{{ formattedArguments }}</pre>
      </el-collapse-transition>
    </div>

    <el-radio-group :value="action" @input="selectAction">
      <el-radio label="APPROVE_ONCE">允许</el-radio>
      <el-radio label="REJECT">拒绝</el-radio>
    </el-radio-group>
  </div>
</template>

<script>
export default {
  name: 'PermissionApprovalStep',
  props: {
    item: { type: Object, required: true },
    action: { type: String, default: '' },
    compact: { type: Boolean, default: false }
  },
  data() {
    return { argsOpen: false }
  },
  computed: {
    toolName() { return this.item?.toolRequest?.name || '' },
    rawArguments() {
      const tool = this.item?.toolRequest || {}
      const payload = this.item?.payload || {}
      const value =
        tool.arguments ?? tool.inputParams ?? payload.arguments ?? payload.inputParams
      if (value === null || value === undefined || value === '') return ''
      return typeof value === 'string' ? value : JSON.stringify(value)
    },
    formattedArguments() {
      if (!this.rawArguments) return '无参数'
      try {
        return JSON.stringify(JSON.parse(this.rawArguments), null, 2)
      } catch (_error) {
        return this.rawArguments
      }
    },
    argumentPreview() {
      const text = this.formattedArguments.replace(/\s+/g, ' ')
      if (text === '无参数') return text
      return text.length > 90 ? `${text.slice(0, 90)}…` : text
    }
  },
  methods: {
    selectAction(action) { this.$emit('update', action) }
  }
}
</script>

<style scoped>
.interrupt-step { display: flex; flex-direction: column; gap: 6px; }
.interrupt-question__lead { display: flex; align-items: flex-start; gap: 8px; }
.interrupt-question__lead > label { flex: 1; min-width: 0; color: #303133; font-size: 14px; font-weight: 600; line-height: 1.45; }
.interrupt-question__lead code { padding: 1px 5px; border-radius: 5px; background: #f6f1e3; color: #7a5a12; font-family: Consolas, Monaco, monospace; font-size: 12px; }
.interrupt-step em { color: #d85a45; font-style: normal; margin-left: 3px; }
.interrupt-question__type { flex: 0 0 auto; margin-top: 2px; padding: 0 6px; border: 1px solid #ecd9a8; border-radius: 999px; background: #fff6df; color: #8a6414; font-size: 10px; font-weight: 600; line-height: 15px; letter-spacing: .3px; }
.interrupt-question__type.is-approval { border-color: #f0d38a; background: #fff3d6; color: #8a6414; }
.interrupt-step > small { color: #7c6b45; font-size: 11.5px; line-height: 1.45; }
.interrupt-question__hint { color: #a08a5c; }
.interrupt-step .el-radio-group { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 10px; width: 100%; }
.interrupt-step .el-radio { display: flex; align-items: center; box-sizing: border-box; min-width: 0; min-height: 32px; margin: 0; padding: 5px 9px; border: 1px solid #ece3cf; border-radius: 7px; background: #fff; white-space: normal; font-size: 12.5px; line-height: 17px; transition: border-color .16s ease, background .16s ease, box-shadow .16s ease; }
.interrupt-step .el-radio:hover { border-color: #d9b45e; background: #fffaf0; }
.interrupt-step .el-radio.is-checked { border-color: #8bc0f3; background: #f2f8ff; box-shadow: inset 0 0 0 1px rgba(64, 158, 255, .18); }
.interrupt-step ::v-deep .el-radio__label { padding-left: 7px; font-size: 12.5px; line-height: 17px; }
.interrupt-step ::v-deep .el-radio.is-checked .el-radio__label { color: #2468a8; font-weight: 500; }

.approval-args { border: 1px solid #f0e4cd; border-radius: 7px; background: #fffdf7; overflow: hidden; }
.approval-args-head { display: flex; align-items: center; gap: 7px; min-width: 0; padding: 5px 9px; cursor: pointer; user-select: none; }
.approval-args-title { flex: 0 0 auto; color: #786536; font-size: 11.5px; font-weight: 600; }
.approval-args-preview { flex: 1; min-width: 0; overflow: hidden; color: #9a8a68; font-size: 11px; font-family: Consolas, Monaco, monospace; text-overflow: ellipsis; white-space: nowrap; }
.approval-args-arrow { flex: 0 0 auto; color: #b8a887; font-size: 11px; transition: transform .2s; }
.approval-args-arrow.open { transform: rotate(180deg); }
.approval-args-body { margin: 0; padding: 7px 9px; border-top: 1px solid #f2e8d5; background: #fbf8f1; color: #5a4d33; font-size: 11px; font-family: Consolas, Monaco, monospace; line-height: 1.5; white-space: pre-wrap; word-break: break-word; max-height: 180px; overflow: auto; }

.is-compact-step { gap: 7px; }
.is-compact-step .approval-args-head { padding: 4px 7px; }
.is-compact-step .approval-args-title { font-size: 10.5px; }
.is-compact-step .approval-args-preview { font-size: 10px; }
.is-compact-step .approval-args-body { padding: 6px 7px; font-size: 10px; max-height: 140px; }
.is-compact-step .interrupt-question__lead > label { font-size: 14px; }
.is-compact-step .interrupt-question__lead code { font-size: 11px; }
.is-compact-step .interrupt-question__type { margin-top: 3px; }
.is-compact-step > small { font-size: 11px; }
.is-compact-step .el-radio { padding: 5px 8px; font-size: 12px; }
.is-compact-step ::v-deep .el-radio__label { font-size: 12px; }

</style>