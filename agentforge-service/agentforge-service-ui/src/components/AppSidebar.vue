<template>
  <aside class="af-sidebar">
    <div class="af-sidebar__head">
      <div class="af-brand">
        <span class="af-brand__logo"><img src="/logo.png" alt="AgentForge" /></span>
        <div><div class="af-brand__name">AgentForge</div><div class="af-brand__sub">AgentForge Service</div></div>
      </div>
    </div>
    <nav class="af-sidebar__scroll">
      <div class="af-nav-group af-nav-group--agent">
        <button class="af-nav-group__head" type="button" :aria-expanded="agentOpen" @click="agentOpen = !agentOpen">
          <span>Agent 驾驶舱</span><NavChevron :collapsed="!agentOpen" />
        </button>
        <transition name="af-fade"><div v-show="agentOpen" class="af-nav-group__body">
          <a class="af-nav-item af-nav-item--link" :class="{ 'af-nav-item--active': active === 'agents' }" href="/agents">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="12" cy="8" r="3"/><path d="M5 20a7 7 0 0 1 14 0M4 5l2 1M20 5l-2 1M12 2v2"/></svg>
            <span class="af-nav-item__label">我的 Agent</span>
          </a>
        </div></transition>
      </div>
      <div class="af-nav-group af-nav-group--resources">
        <button class="af-nav-group__head" type="button" :aria-expanded="resourcesOpen" @click="resourcesOpen = !resourcesOpen">
          <span>我的资源库</span><NavChevron :collapsed="!resourcesOpen" />
        </button>
        <transition name="af-fade"><div v-show="resourcesOpen" class="af-nav-group__body">
          <a class="af-nav-item af-nav-item--link" :class="{ 'af-nav-item--active': active === 'models' }" href="/">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2l8 4.5v9L12 20l-8-4.5v-9L12 2z"/><path d="M12 11L4 6.5M12 11l8-4.5M12 11v9"/></svg>
            <span class="af-nav-item__label">模型列表</span>
          </a>
        </div></transition>
      </div>
    </nav>
    <div class="af-sidebar__footer"><AppUserPanel /></div>
  </aside>
</template>

<script>
import AppUserPanel from './AppUserPanel.vue'

const NavChevron = {
  functional: true,
  props: { collapsed: Boolean },
  render(h, context) {
    return h('svg', { class: ['af-nav-group__chevron', { 'af-nav-group__chevron--collapsed': context.props.collapsed }], attrs: { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '2', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' } }, [h('path', { attrs: { d: 'M6 9l6 6 6-6' } })])
  }
}

export default {
  name: 'AppSidebar',
  components: { AppUserPanel, NavChevron },
  props: { active: { type: String, required: true, validator: (value) => ['agents', 'models'].includes(value) } },
  data: () => ({ agentOpen: true, resourcesOpen: true })
}
</script>
