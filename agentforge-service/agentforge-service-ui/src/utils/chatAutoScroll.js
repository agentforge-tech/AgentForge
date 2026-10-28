/**
 * 会话消息区滚动行为：
 * 1. AI 流式回复时，只有用户停留在底部附近才自动跟随；
 * 2. 用户主动上滑后立即停止强制滚动，并显示“回到底部”按钮；
 * 3. 判断完全基于滚动位置，程序化滚到底部后距离为 0，不会误判为用户上滑。
 */
const NEAR_BOTTOM_THRESHOLD = 80

export default {
  data() {
    return { isUserScrolledUp: false }
  },
  methods: {
    /** 消息区滚动监听：离开底部即标记为“已上滑”，回到阈值内自动恢复跟随。 */
    onFeedScroll() {
      const el = this.$refs.feed
      if (!el) return
      if (el.scrollHeight <= el.clientHeight) {
        this.isUserScrolledUp = false
        return
      }
      const distance = el.scrollHeight - el.scrollTop - el.clientHeight
      this.isUserScrolledUp = distance > NEAR_BOTTOM_THRESHOLD
    },
    /**
     * 滚动到底部。
     * @param {'auto'|'smooth'} behavior 滚动动画
     * @param {boolean} force 忽略“用户已上滑”状态，强制滚动
     */
    scroll(behavior = 'auto', force = false) {
      this.$nextTick(() => {
        const el = this.$refs.feed
        if (!el) return
        if (!force && this.isUserScrolledUp) return
        if (behavior === 'smooth') el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
        else el.scrollTop = el.scrollHeight
      })
    },
    /** 点击“回到底部”：恢复自动跟随并立即滚动到最新消息。 */
    scrollToBottom() {
      this.isUserScrolledUp = false
      this.scroll('auto', true)
    },
    /** 会话/消息整体切换时重置跟随状态。 */
    resetScrollFollow() {
      this.isUserScrolledUp = false
    }
  }
}
