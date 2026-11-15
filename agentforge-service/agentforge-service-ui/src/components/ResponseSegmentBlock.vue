<template>
  <div class="response-segment" :class="mode">
    {{ displayContent }}
  </div>
</template>

<script>
export default {
  name: 'ResponseSegmentBlock',
  props: {
    part: { type: Object, required: true },
    mode: { type: String, default: 'elegant' }
  },
  computed: {
    displayContent() {
      return String(this.part.content || '').trim()
    }
  }
}
</script>

<style scoped>
.response-segment {
  min-width: 0;
  padding: 2px 0 5px;
  color: #46566c;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.response-segment.elegant { color: #58677a; }
</style>
