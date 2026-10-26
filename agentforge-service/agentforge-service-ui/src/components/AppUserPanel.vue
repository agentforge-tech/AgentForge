<template>
  <div class="af-shared-user">
    <span class="af-shared-user__avatar">{{ initial }}</span>
    <div class="af-shared-user__meta">
      <div class="af-shared-user__name">{{ name }}</div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'AppUserPanel',
  props: { name: { type: String, default: 'Agent 开发者' } },
  computed: { initial() { return (this.name || 'A').trim().charAt(0).toUpperCase() } }
}
</script>

<style scoped>
.af-shared-user { display: flex; align-items: center; gap: 10px; padding: 6px; border-radius: 10px; transition: background .15s ease; }
.af-shared-user:hover { background: #eef1f6; }
.af-shared-user__avatar { display: flex; align-items: center; justify-content: center; width: 30px; height: 30px; flex-shrink: 0; border-radius: 50%; background: linear-gradient(135deg, #7cb0ff, #4b8ef7); color: #fff; font-size: 12px; font-weight: 600; box-shadow: 0 2px 7px rgba(75,142,247,.22); }
.af-shared-user__meta { flex: 1; min-width: 0; }
.af-shared-user__name { overflow: hidden; color: #24292f; font-size: 12.5px; font-weight: 600; white-space: nowrap; text-overflow: ellipsis; }
</style>
