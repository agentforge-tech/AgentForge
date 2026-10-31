<template>
  <div class="af-markdown" :class="[`af-markdown--${normalizedMode}`, { 'is-streaming': animationActive }]">
    <div ref="stable" class="markdown-body markdown-body--stable" v-html="stableHtml"></div>
    <div ref="tail" class="markdown-body markdown-body--tail" v-html="tailHtml"></div>
  </div>
</template>

<script>
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import DOMPurify from 'dompurify'
import katex from 'katex'
import 'highlight.js/styles/github.css'
import 'katex/dist/katex.min.css'

export const MARKDOWN_STREAM_MODES = Object.freeze({ INSTANT: 'instant', TYPEWRITER: 'typewriter', TYPEWRITER_BLUR: 'typewriter-blur' })
const VALID_MODES = Object.values(MARKDOWN_STREAM_MODES)
const toChars = (value) => Array.from(value || '')

function installMath(md) {
  md.inline.ruler.after('escape', 'af_math_inline', (state, silent) => {
    if (state.src[state.pos] !== '$' || state.src[state.pos + 1] === '$') return false
    const end = state.src.indexOf('$', state.pos + 1)
    if (end < 0 || end === state.pos + 1) return false
    if (!silent) { const token = state.push('af_math_inline', 'math', 0); token.content = state.src.slice(state.pos + 1, end) }
    state.pos = end + 1
    return true
  })
  md.block.ruler.after('blockquote', 'af_math_block', (state, startLine, endLine, silent) => {
    const start = state.bMarks[startLine] + state.tShift[startLine]
    if (state.src.slice(start, state.eMarks[startLine]).trim() !== '$$') return false
    let nextLine = startLine + 1
    while (nextLine < endLine) {
      const lineStart = state.bMarks[nextLine] + state.tShift[nextLine]
      if (state.src.slice(lineStart, state.eMarks[nextLine]).trim() === '$$') break
      nextLine++
    }
    if (nextLine >= endLine) return false
    if (silent) return true
    const token = state.push('af_math_block', 'math', 0)
    token.block = true
    token.content = state.getLines(startLine + 1, nextLine, state.tShift[startLine], true).trim()
    token.map = [startLine, nextLine + 1]
    state.line = nextLine + 1
    return true
  })
  md.renderer.rules.af_math_inline = (tokens, idx) => katex.renderToString(tokens[idx].content, { throwOnError: false, strict: false })
  md.renderer.rules.af_math_block = (tokens, idx) => `<div class="math-block">${katex.renderToString(tokens[idx].content, { displayMode: true, throwOnError: false, strict: false })}</div>`
}

function createMarkdown(highlightCode) {
  const md = new MarkdownIt({
    html: false, linkify: true, breaks: true,
    highlight(str, lang) {
      if (highlightCode && lang && hljs.getLanguage(lang)) {
        try { return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang }).value}</code></pre>` } catch (_error) { /* fallback below */ }
      }
      return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
    }
  })
  installMath(md)
  const defaultLinkOpen = md.renderer.rules.link_open || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    tokens[idx].attrSet('target', '_blank'); tokens[idx].attrSet('rel', 'noopener noreferrer')
    return defaultLinkOpen(tokens, idx, options, env, self)
  }
  return md
}

const streamingMd = createMarkdown(false)
const finalMd = createMarkdown(true)
const sanitize = (html) => DOMPurify.sanitize(html, { USE_PROFILES: { html: true, svg: true, mathMl: true }, ADD_ATTR: ['target'] })

function splitBlocks(text) {
  const matches = [...text.matchAll(/\n{2,}/g)]
  if (!matches.length) return { stableText: '', tailText: text }
  const last = matches[matches.length - 1]
  const boundary = last.index + last[0].length
  return { stableText: text.slice(0, boundary), tailText: text.slice(boundary) }
}

export default {
  name: 'MarkdownView',
  props: {
    content: { type: String, default: '' },
    streaming: { type: Boolean, default: false },
    mode: { type: String, default: MARKDOWN_STREAM_MODES.TYPEWRITER_BLUR, validator: (value) => VALID_MODES.includes(value) },
    charDelay: { type: Number, default: 40 },
    maxLag: { type: Number, default: 60 },
    blurTail: { type: Number, default: 2 }
  },
  data() {
    return { stableHtml: '', tailHtml: '', visibleLength: 0, lastStableText: null, lastFrameAt: 0, raf: null, draining: false }
  },
  computed: {
    normalizedMode() { return VALID_MODES.includes(this.mode) ? this.mode : MARKDOWN_STREAM_MODES.TYPEWRITER_BLUR },
    animationActive() { return this.streaming || this.draining }
  },
  watch: {
    content: { immediate: true, handler() { this.syncContent() } },
    streaming(value, previous) {
      const hasBufferedText = this.visibleLength < toChars(this.content).length
      if (!value && previous && this.normalizedMode !== MARKDOWN_STREAM_MODES.INSTANT && hasBufferedText) {
        this.draining = true
        this.scheduleFrame()
        return
      }
      this.syncContent(true)
    },
    mode() { this.syncContent(true) }
  },
  beforeDestroy() { this.cancelFrame() },
  methods: {
    render(text, final = false) { return sanitize((final ? finalMd : streamingMd).render(text || '')) },
    syncContent(force = false) {
      if (!this.animationActive || this.normalizedMode === MARKDOWN_STREAM_MODES.INSTANT) { this.finishRender(!this.streaming); return }
      const total = toChars(this.content).length
      if (force && this.visibleLength > total) this.visibleLength = 0
      if (this.visibleLength < total) this.scheduleFrame()
    },
    scheduleFrame() { if (this.raf == null) this.raf = requestAnimationFrame(this.tick) },
    cancelFrame() { if (this.raf != null) cancelAnimationFrame(this.raf); this.raf = null },
    tick(now) {
      this.raf = null
      if (!this.animationActive || this.normalizedMode === MARKDOWN_STREAM_MODES.INSTANT) { this.syncContent(); return }
      const chars = toChars(this.content)
      if (this.visibleLength >= chars.length) return
      if (!this.lastFrameAt || now - this.lastFrameAt >= Math.max(0, this.charDelay)) {
        const lag = chars.length - this.visibleLength
        const step = lag > this.maxLag ? Math.ceil(lag / 20) : 1
        this.visibleLength = Math.min(chars.length, this.visibleLength + step)
        this.lastFrameAt = now
        this.renderVisible(chars.slice(0, this.visibleLength).join(''))
      }
      if (this.visibleLength < chars.length) this.scheduleFrame()
      else if (this.draining) { this.draining = false; this.finishRender(true) }
    },
    renderVisible(visibleText) {
      const { stableText, tailText } = splitBlocks(visibleText)
      if (stableText !== this.lastStableText) { this.stableHtml = this.render(stableText); this.lastStableText = stableText }
      this.tailHtml = this.render(tailText)
      this.$nextTick(this.decorateTail)
    },
    finishRender(final) {
      this.cancelFrame(); this.draining = false; this.visibleLength = toChars(this.content).length; this.lastFrameAt = 0
      this.lastStableText = this.content || ''; this.stableHtml = this.render(this.content, final); this.tailHtml = ''
      if (final) {
        const renderedContent = this.content
        this.$nextTick(() => this.$emit('render-complete', { content: renderedContent }))
      }
    },
    decorateTail() {
      const root = this.$refs.tail
      if (!root || !this.animationActive) return
      root.querySelectorAll('.af-stream-caret').forEach((node) => node.remove())
      if (this.normalizedMode === MARKDOWN_STREAM_MODES.TYPEWRITER_BLUR) { this.wrapLastTextChars(root, Math.max(1, this.blurTail)); return }
      if (this.normalizedMode !== MARKDOWN_STREAM_MODES.TYPEWRITER) return
      const caret = document.createElement('span'); caret.className = 'af-stream-caret'; caret.setAttribute('aria-hidden', 'true')
      const last = this.lastTextNode(root)
      if (last && last.parentNode) last.parentNode.insertBefore(caret, last.nextSibling); else root.appendChild(caret)
    },
    textNodes(root) {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT); const nodes = []; let node
      while ((node = walker.nextNode())) {
        const parent = node.parentElement
        if (parent && !parent.closest('pre, code, .katex, .af-char-blur')) nodes.push(node)
      }
      return nodes
    },
    lastTextNode(root) { const nodes = this.textNodes(root); return nodes[nodes.length - 1] || null },
    wrapLastTextChars(root, count) {
      const nodes = this.textNodes(root); let remaining = count
      for (let i = nodes.length - 1; i >= 0 && remaining > 0; i--) {
        const node = nodes[i]; const chars = toChars(node.nodeValue); if (!chars.length) continue
        const take = Math.min(remaining, chars.length); const fragment = document.createDocumentFragment()
        const head = chars.slice(0, chars.length - take).join(''); if (head) fragment.appendChild(document.createTextNode(head))
        chars.slice(chars.length - take).forEach((char) => { const span = document.createElement('span'); span.className = 'af-char-blur'; span.textContent = char; fragment.appendChild(span) })
        node.parentNode.replaceChild(fragment, node); remaining -= take
      }
    }
  }
}
</script>

<style>
.af-markdown { min-width: 0; overflow-wrap: anywhere; word-break: normal; }
.af-markdown.is-streaming { overflow-anchor: none; }
.markdown-body { display: flow-root; font-size: 14px; line-height: 1.72; color: #24292f; }
.markdown-body > *:first-child { margin-top: 0; }
.markdown-body > *:last-child { margin-bottom: 0; }
/* stable/tail 流式时分属两个容器，完成后合并。必须恢复 tail 首元素间距，否则 :first-child 身份变化会导致结束瞬间跳位。 */
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > p:first-child,
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > ul:first-child,
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > ol:first-child { margin-top: 8px; }
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > h1:first-child,
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > h2:first-child,
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > h3:first-child,
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > h4:first-child { margin-top: 16px; }
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > blockquote:first-child,
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > pre:first-child,
.markdown-body--stable:not(:empty) + .markdown-body--tail:not(:empty) > table:first-child { margin-top: 10px; }
.markdown-body p { margin: 8px 0; }
.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4 { margin: 16px 0 8px; font-weight: 600; line-height: 1.3; }
.markdown-body h1 { font-size: 20px; border-bottom: 1px solid #eaecef; padding-bottom: 6px; }
.markdown-body h2 { font-size: 18px; border-bottom: 1px solid #eaecef; padding-bottom: 5px; }
.markdown-body h3 { font-size: 16px; }
.markdown-body table { border-collapse: collapse; width: 100%; margin: 10px 0; display: block; overflow-x: auto; }
.markdown-body th, .markdown-body td { border: 1px solid #dfe2e5; padding: 6px 10px; }
.markdown-body th { background: #f6f8fa; font-weight: 600; }
.markdown-body blockquote { margin: 8px 0; padding: 4px 12px; color: #6a737d; border-left: 4px solid #dfe2e5; background: #fafbfc; }
.markdown-body code { background: rgba(27,31,35,.06); border-radius: 4px; padding: 2px 5px; font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px; }
.markdown-body pre { background: #f6f8fa; border-radius: 8px; padding: 12px; overflow: auto; margin: 10px 0; }
.markdown-body pre code { background: transparent; padding: 0; }
.markdown-body ul, .markdown-body ol { padding-left: 22px; margin: 8px 0; }
.markdown-body li { margin: 3px 0; }
.markdown-body a { color: #0969da; text-decoration: none; }
.markdown-body a:hover { text-decoration: underline; }
.markdown-body img { max-width: 100%; border-radius: 8px; }
.markdown-body hr { border: 0; border-top: 1px solid #eaecef; margin: 14px 0; }
.markdown-body .math-block { margin: 12px 0; overflow-x: auto; text-align: center; }
.af-stream-caret { display: inline-block; width: 2px; height: 1em; margin-left: 2px; background: #3b6fb6; vertical-align: -.15em; animation: af-markdown-blink 1s steps(2,start) infinite; }
.af-char-blur { animation: af-markdown-char-blur 70ms ease-out both; }
@keyframes af-markdown-blink { 0%,49% { opacity: 1; } 50%,100% { opacity: 0; } }
@keyframes af-markdown-char-blur { from { filter: blur(.9px); opacity: .68; } to { filter: blur(0); opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .af-stream-caret, .af-char-blur { animation: none; } }
</style>
