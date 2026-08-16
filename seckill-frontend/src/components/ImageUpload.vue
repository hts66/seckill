<script setup>
import { ref, watch } from 'vue'
import { uploadImage } from '../api/product'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  maxCount: { type: Number, default: 5 },
})

const emit = defineEmits(['update:modelValue', 'image-removed'])
const uploading = ref(false)
const uploadMsg = ref('')

const images = ref([...props.modelValue])

// 父组件更新 modelValue 时同步
watch(() => props.modelValue, (val) => {
  images.value = [...(val || [])]
})

async function handleFileChange(e) {
  const files = e.target.files
  if (!files || files.length === 0) return

  uploading.value = true
  uploadMsg.value = ''
  let successCount = 0
  let failCount = 0

  for (const file of files) {
    if (images.value.length >= props.maxCount) {
      uploadMsg.value = `最多上传 ${props.maxCount} 张图片`
      break
    }
    try {
      const res = await uploadImage(file)
      // res 是响应 data（拦截器已解包）
      if (res && res.data && res.data.url) {
        images.value.push(res.data.url)
        successCount++
      } else {
        failCount++
      }
    } catch (err) {
      failCount++
      console.error('上传失败:', err)
      // 尝试提取详细错误
      const detail = err?.response?.data?.message || err.message || '未知错误'
      uploadMsg.value = `第 ${images.value.length + 1} 张上传失败: ${detail}`
    }
  }

  if (successCount > 0) {
    emit('update:modelValue', [...images.value])
    uploadMsg.value = `成功上传 ${successCount} 张` + (failCount > 0 ? `，${failCount} 张失败` : '')
  }

  uploading.value = false
  e.target.value = ''
}

function removeImage(index) {
  const url = images.value[index]
  images.value.splice(index, 1)
  emit('update:modelValue', [...images.value])
  // 是否立即删除由父组件根据图片是否已经保存来决定。
  // 已保存的图片必须等商品更新成功后才能从 MinIO 删除。
  if (url) emit('image-removed', url)
}

function onImageError(e) {
  // 图片加载失败时显示占位符
  e.target.style.display = 'none'
  const placeholder = e.target.nextElementSibling
  if (placeholder) placeholder.style.display = 'flex'
}
</script>

<template>
  <div class="image-upload">
    <div class="image-grid">
      <div
        v-for="(url, index) in images"
        :key="index"
        class="image-item"
      >
        <img
          :src="url"
          :alt="'商品图片 ' + (index + 1)"
          @error="onImageError"
        />
        <div class="broken-placeholder" style="display:none;">
          <span style="font-size:20px;">🖼️</span>
          <span style="font-size:10px;color:var(--text-light);">加载失败</span>
        </div>
        <span class="remove-btn" @click="removeImage(index)">×</span>
        <span v-if="index === 0" class="cover-badge">封面</span>
      </div>

      <!-- 上传按钮 -->
      <label v-if="images.length < maxCount" class="upload-btn" :class="{ disabled: uploading }">
        <input
          type="file"
          accept="image/*"
          :disabled="uploading"
          @change="handleFileChange"
          style="display:none"
        />
        <div v-if="uploading" class="uploading-text">
          <span class="pulse">⏳</span>
          <span style="font-size:11px;">上传中...</span>
        </div>
        <div v-else>
          <span style="font-size:28px;">+</span>
          <p style="font-size:12px;margin-top:4px;color:var(--text-light);">上传图片</p>
        </div>
      </label>
    </div>

    <!-- 上传消息 -->
    <p v-if="uploadMsg" class="upload-msg" :class="{ error: uploadMsg.includes('失败') }">
      {{ uploadMsg }}
    </p>

    <p style="font-size:12px;color:var(--text-light);margin-top:4px;">
      支持 JPG/PNG，最多 {{ maxCount }} 张，首张为封面图
    </p>
  </div>
</template>

<style scoped>
.image-grid {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.image-item {
  width: 100px;
  height: 100px;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
  border: 2px solid var(--border);
  flex-shrink: 0;
  background: var(--bg);
}
.image-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.broken-placeholder {
  position: absolute;
  top: 0; left: 0;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}
.image-item .remove-btn {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 20px;
  height: 20px;
  background: rgba(0,0,0,0.6);
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  cursor: pointer;
  line-height: 1;
  z-index: 2;
}
.cover-badge {
  position: absolute;
  bottom: 2px;
  left: 2px;
  background: var(--primary);
  color: #fff;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  z-index: 2;
}
.upload-btn {
  width: 100px;
  height: 100px;
  border: 2px dashed var(--border);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all .2s;
  flex-shrink: 0;
  color: var(--text-light);
}
.upload-btn:hover { border-color: var(--primary); color: var(--primary); }
.upload-btn.disabled { opacity: 0.5; cursor: not-allowed; }
.uploading-text {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.upload-msg {
  font-size: 12px;
  margin-top: 6px;
  color: var(--success);
}
.upload-msg.error { color: var(--primary); }
</style>
