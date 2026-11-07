export const TODO_PLAN_TOOL_NAME = 'todoplan'

const STATUS_KEYS = { 1: 'pending', 2: 'in_progress', 3: 'completed', 4: 'cancelled' }
const PRIORITY_KEYS = { 1: 'high', 2: 'medium', 3: 'low' }

export function buildTodoCardPart(toolPart) {
  if (!toolPart || toolPart.toolName !== TODO_PLAN_TOOL_NAME) return null
  const params = parseInputParams(toolPart.inputParams)
  if (!params || !Array.isArray(params.todos)) return null

  const todos = params.todos
    .filter((item) => item && item.content)
    .map((item) => ({
      ...item,
      statusKey: STATUS_KEYS[Number(item.status)] || 'pending',
      priorityKey: PRIORITY_KEYS[Number(item.priority)] || 'low'
    }))
    .sort((left, right) => Number(left.seq || 0) - Number(right.seq || 0))
  if (!todos.length) {
    return {
      type: 'todo_card',
      visible: false,
      toolCallId: toolPart.toolCallId,
      todos: [],
      summary: { total: 0, pending: 0, inProgress: 0, completed: 0, cancelled: 0 }
    }
  }

  return {
    type: 'todo_card',
    visible: true,
    toolCallId: toolPart.toolCallId,
    todos,
    summary: {
      total: todos.length,
      pending: count(todos, 'pending'),
      inProgress: count(todos, 'in_progress'),
      completed: count(todos, 'completed'),
      cancelled: count(todos, 'cancelled')
    }
  }
}

function count(todos, statusKey) {
  return todos.filter((item) => item.statusKey === statusKey).length
}

function parseInputParams(value) {
  if (value == null || typeof value === 'object') return value
  if (typeof value !== 'string' || !value.trim()) return null
  try {
    return JSON.parse(value)
  } catch (_error) {
    return null
  }
}
