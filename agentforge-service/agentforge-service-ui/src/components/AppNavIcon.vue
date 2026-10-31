<template>
  <span class="af-nav-icon" aria-hidden="true">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
    <template v-if="name === 'components'">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <path d="M17.5 14v7M14 17.5h7" />
    </template>
    <template v-else-if="name === 'models'">
      <path d="m12 3 7.5 4.3v9.4L12 21l-7.5-4.3V7.3L12 3Z" />
      <circle cx="12" cy="12" r="2.5" />
      <path d="M12 7.5V9.5M8.1 9.8l1.7 1M15.9 9.8l-1.7 1M12 14.5v2" />
    </template>
    <template v-else-if="name === 'plugins'">
      <path d="M8.5 3v4M15.5 3v4" />
      <path d="M6 7h12v3a6 6 0 0 1-6 6v0a6 6 0 0 1-6-6V7Z" />
      <path d="M12 16v5" />
    </template>
    <template v-else-if="name === 'mcp'">
      <circle cx="6" cy="12" r="2.5" />
      <circle cx="18" cy="6" r="2.5" />
      <circle cx="18" cy="18" r="2.5" />
      <path d="m8.2 10.9 7.6-3.8M8.2 13.1l7.6 3.8" />
    </template>
    <template v-else-if="name === 'http'">
      <path d="M10 13a5 5 0 0 0 7.5.5l2-2a5 5 0 0 0-7-7l-1.1 1.1" />
      <path d="M14 11a5 5 0 0 0-7.5-.5l-2 2a5 5 0 0 0 7 7l1.1-1.1" />
    </template>
    <template v-else-if="name === 'cockpit'">
      <circle cx="12" cy="12" r="8.5" />
      <circle cx="12" cy="12" r="4.5" stroke-dasharray="2 2" />
      <path d="m12 12 5-3M12 3.5v2M20.5 12h-2M12 20.5v-2M3.5 12h2" />
      <circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none" />
    </template>
    <template v-else-if="name === 'agents'">
      <rect x="4" y="6" width="16" height="14" rx="3" />
      <path d="M12 3v3M8 12h.01M16 12h.01M8.5 16h7" />
    </template>
    <template v-else-if="name === 'trace'">
      <path d="M4 18h16M6 15V9M10 15V5M14 15v-3M18 15V7" />
      <path d="m5 8 4-4 5 7 5-5" />
    </template>
    </svg>
  </span>
</template>

<script>
export default {
  name: 'AppNavIcon',
  props: {
    name: { type: String, required: true }
  }
}
</script>

<style>
.af-nav-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  flex: 0 0 16px;
  margin-right: 8px;
  color: inherit;
  vertical-align: middle;
}

.af-nav-icon svg {
  width: 16px;
  height: 16px;
}

.af-menu .el-submenu__title,
.af-menu .el-menu-item {
  display: flex;
  align-items: center;
}
</style>
