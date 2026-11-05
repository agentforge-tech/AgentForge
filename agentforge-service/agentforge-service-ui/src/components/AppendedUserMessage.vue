<template>
  <div class="appended-user-message">
    <span class="appended-user-message__mark" aria-hidden="true">›</span>
    <div class="appended-user-message__content">{{ part.content }}</div>
  </div>
</template>

<script>
export default {
  name: 'AppendedUserMessage',
  props: { part: { type: Object, required: true } }
}
</script>

<style scoped>
.appended-user-message {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  width: 35%;
  max-width: 100%;
  min-width: 280px;
  box-sizing: border-box;
  margin: 4px 0 10px;
  padding: 7px 10px;
  color: #303133;
  background: rgba(64, 158, 255, 0.045);
  border: 0;
  border-left: 2px solid rgba(64, 158, 255, 0.72);
  border-radius: 0 5px 5px 0;
  animation: appended-message-in 0.2s ease-out;
}
.appended-user-message__mark { flex-shrink: 0; color: #7891b2; font-size: 15px; line-height: 1.45; }
.appended-user-message__content { min-width: 0; flex: 1; color: #606266; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
@keyframes appended-message-in { from { opacity: 0; transform: translateY(3px); } to { opacity: 1; transform: translateY(0); } }
@media (max-width: 768px) { .appended-user-message { width: 70%; min-width: 250px; max-width: 350px; } }
@media (max-width: 480px) { .appended-user-message { width: 85%; min-width: 220px; max-width: 300px; } }
</style>
