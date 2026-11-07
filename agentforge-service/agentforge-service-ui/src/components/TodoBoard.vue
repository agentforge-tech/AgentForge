<template>
  <div v-if="state.visible" class="todo-board" :class="{ 'todo-board--running': running }">
    <div class="todo-board__head" @click="open = !open">
      <span class="todo-board__icon"><i class="el-icon-tickets"></i></span>
      <span class="todo-board__title">{{ title }}</span>
      <span class="todo-board__meta">{{ completedCount }}/{{ effectiveTotal }} 完成</span>
      <span class="todo-board__percent">{{ percent }}%</span>
      <i class="el-icon-arrow-down todo-board__arrow" :class="{ 'is-open': open }"></i>
    </div>
    <div class="todo-board__progress"><span :style="{ width: percent + '%' }"></span></div>
    <el-collapse-transition>
      <div v-show="open" class="todo-board__body">
        <div v-if="inProgressText" class="todo-board__current">
          <i class="el-icon-loading"></i>
          <span>正在执行：{{ inProgressText }}</span>
        </div>
        <ul class="todo-board__list">
          <li
            v-for="(item, index) in state.todos"
            :key="index"
            class="todo-board__item"
            :class="'is-' + item.statusKey"
          >
            <i :class="statusIcon(item.statusKey)"></i>
            <span class="todo-board__seq">{{ item.seq }}</span>
            <span class="todo-board__content" :title="item.content">{{ item.content }}</span>
            <span v-if="detailed" class="todo-board__priority" :class="'is-' + item.priorityKey">
              {{ priorityLabel(item.priorityKey) }}
            </span>
          </li>
        </ul>
        <div v-if="detailed && updatedAtText" class="todo-board__time">更新于 {{ updatedAtText }}</div>
      </div>
    </el-collapse-transition>
  </div>
</template>

<script>
export default {
  name: 'TodoBoard',
  props: {
    state: { type: Object, required: true },
    detailed: { type: Boolean, default: false },
    running: { type: Boolean, default: false }
  },
  data() {
    return { open: true }
  },
  computed: {
    summary() {
      return this.state.summary || {}
    },
    total() {
      return Number(this.summary.total || 0)
    },
    completedCount() {
      return Number(this.summary.completed || 0)
    },
    cancelledCount() {
      return Number(this.summary.cancelled || 0)
    },
    effectiveTotal() {
      return Math.max(0, this.total - this.cancelledCount)
    },
    percent() {
      if (!this.effectiveTotal) return this.total ? 100 : 0
      return Math.min(100, Math.round((this.completedCount / this.effectiveTotal) * 100))
    },
    title() {
      if (this.total > 0 && this.completedCount >= this.effectiveTotal) return '任务已完成'
      return this.running ? '任务执行中' : '任务计划'
    },
    inProgressText() {
      const item = (this.state.todos || []).find((todo) => todo.statusKey === 'in_progress')
      return item ? item.content : ''
    },
    updatedAtText() {
      if (!this.state.updatedAt) return ''
      const date = new Date(this.state.updatedAt)
      if (Number.isNaN(date.getTime())) return ''
      return date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })
    }
  },
  methods: {
    statusIcon(status) {
      if (status === 'completed') return 'el-icon-circle-check'
      if (status === 'cancelled') return 'el-icon-remove-outline'
      if (status === 'in_progress') return 'el-icon-loading'
      return 'el-icon-time'
    },
    priorityLabel(priority) {
      const labels = { high: '高', medium: '中', low: '低' }
      return labels[priority] || ''
    }
  }
}
</script>

<style scoped>
.todo-board {
  margin: 10px 0 14px;
  overflow: hidden;
  border: 1px solid #e2ebf6;
  border-radius: 12px;
  background: linear-gradient(180deg, #fbfdff 0%, #f5f9ff 100%);
  box-shadow: 0 4px 16px rgba(45, 82, 128, 0.06);
}
.todo-board__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 13px 8px;
  cursor: pointer;
  user-select: none;
}
.todo-board__icon {
  display: inline-flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: #fff;
  color: #3b6fb6;
  box-shadow: 0 1px 4px rgba(45, 82, 128, 0.1);
  font-size: 13px;
}
.todo-board__title {
  color: #344054;
  font-size: 13px;
  font-weight: 600;
}
.todo-board__meta {
  margin-left: auto;
  color: #7b889b;
  font-size: 11.5px;
}
.todo-board__percent {
  color: #3b6fb6;
  font-size: 11.5px;
  font-weight: 600;
}
.todo-board__arrow {
  color: #aeb7c4;
  font-size: 11px;
  transition: transform 0.2s;
}
.todo-board__arrow.is-open {
  transform: rotate(180deg);
}
.todo-board__progress {
  height: 3px;
  overflow: hidden;
  background: #e6eef8;
}
.todo-board__progress span {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: linear-gradient(90deg, #7eb1f0, #3b6fb6);
  transition: width 0.3s ease;
}
.todo-board__body {
  padding: 9px 13px 11px;
}
.todo-board__current {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 7px 9px;
  border-radius: 8px;
  background: #edf5ff;
  color: #3b6fb6;
  font-size: 12px;
}
.todo-board__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.todo-board__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 7px;
  border-radius: 7px;
  color: #526071;
  font-size: 12.5px;
  line-height: 1.5;
}
.todo-board__item > i {
  flex: 0 0 auto;
  color: #c0cad8;
  font-size: 14px;
}
.todo-board__item.is-completed {
  color: #8a97a8;
}
.todo-board__item.is-completed .todo-board__content {
  text-decoration: line-through;
}
.todo-board__item.is-completed > i {
  color: #67a35b;
}
.todo-board__item.is-cancelled {
  color: #aab3c0;
}
.todo-board__item.is-cancelled .todo-board__content {
  text-decoration: line-through;
}
.todo-board__item.is-cancelled > i {
  color: #b9c1cc;
}
.todo-board__item.is-in_progress {
  background: #edf5ff;
  color: #2f527b;
}
.todo-board__item.is-in_progress > i {
  color: #3b6fb6;
}
.todo-board__seq {
  flex: 0 0 auto;
  min-width: 16px;
  color: #a3afbf;
  font-size: 10.5px;
  text-align: right;
}
.todo-board__content {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.todo-board__priority {
  flex: 0 0 auto;
  padding: 1px 5px;
  border-radius: 4px;
  background: #f0f3f8;
  color: #98a2b3;
  font-size: 10.5px;
}
.todo-board__priority.is-high {
  background: #fff0ed;
  color: #c05a49;
}
.todo-board__priority.is-medium {
  background: #fff7e8;
  color: #b27a2c;
}
.todo-board__priority.is-low {
  background: #f1f4f8;
  color: #8995a5;
}
.todo-board__time {
  margin-top: 8px;
  color: #a3afbf;
  font-size: 10.5px;
  text-align: right;
}
</style>
