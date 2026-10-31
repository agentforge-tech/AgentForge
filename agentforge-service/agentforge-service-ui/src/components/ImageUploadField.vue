<template>
  <div class="af-image-upload-field">
    <span class="af-image-upload-field__preview" :class="{ 'is-round': round }">
      <img v-if="value && !imageError" :src="value" alt="" @error="imageError = true" />
      <i v-else :class="placeholderIcon"></i>
    </span>
    <div class="af-image-upload-field__actions">
      <el-upload
        action=""
        accept="image/png,image/jpeg,image/webp,image/gif"
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="handleUpload"
        :disabled="uploading"
      >
        <el-button size="small" icon="el-icon-upload2" :loading="uploading">
          {{ uploading ? '上传中' : (value ? '更换图片' : '上传图片') }}
        </el-button>
      </el-upload>
      <el-button v-if="value" size="small" type="text" class="af-text-danger" :disabled="uploading" @click="clear">移除</el-button>
      <div class="af-image-upload-field__tip">PNG、JPG、WebP 或 GIF，不超过 10MB</div>
    </div>
  </div>
</template>

<script>
import { uploadFile } from '../utils/fileUpload'

export default {
  name: 'ImageUploadField',
  props: {
    value: { type: String, default: '' },
    round: { type: Boolean, default: false },
    placeholderIcon: { type: String, default: 'el-icon-picture-outline' }
  },
  data() {
    return { uploading: false, imageError: false }
  },
  watch: {
    value() { this.imageError = false }
  },
  methods: {
    beforeUpload(file) {
      if (!['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(file.type)) {
        this.$message.error('仅支持 PNG、JPG、WebP 或 GIF 图片')
        return false
      }
      if (file.size > 10 * 1024 * 1024) {
        this.$message.error('图片大小不能超过 10MB')
        return false
      }
      return true
    },
    async handleUpload(options) {
      this.uploading = true
      try {
        const result = await uploadFile(options.file)
        this.imageError = false
        this.$emit('input', result.visitUrl)
        this.$emit('uploaded', result)
        options.onSuccess(result)
      } catch (error) {
        options.onError(error)
        this.$message.error(error.message || '图片上传失败')
      } finally {
        this.uploading = false
      }
    },
    clear() {
      this.imageError = false
      this.$emit('input', '')
    }
  }
}
</script>

<style scoped>
.af-image-upload-field { display: flex; align-items: center; gap: 14px; }
.af-image-upload-field__preview { display: flex; width: 64px; height: 64px; flex: 0 0 64px; align-items: center; justify-content: center; overflow: hidden; border: 1px solid #dcdfe6; border-radius: 12px; background: #f5f7fa; color: #a8abb2; }
.af-image-upload-field__preview.is-round { border-radius: 50%; }
.af-image-upload-field__preview img { width: 100%; height: 100%; object-fit: cover; }
.af-image-upload-field__preview i { font-size: 24px; }
.af-image-upload-field__actions { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 10px; }
.af-image-upload-field__tip { width: 100%; color: #909399; font-size: 12px; line-height: 1.4; }
</style>
