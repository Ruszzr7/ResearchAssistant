<template>
  <el-dialog v-model="visible" :title="isEdit ? '编辑笔记' : '新建笔记'" width="520px" @closed="onClose">
    <el-form label-width="60px">
      <el-form-item label="标题">
        <el-input v-model="form.title" placeholder="笔记标题" />
      </el-form-item>
      <el-form-item label="内容">
        <el-input v-model="form.content" type="textarea" :rows="6" placeholder="记录你的想法..." />
      </el-form-item>
      <el-form-item v-if="form.anchorText" label="原文">
        <blockquote class="anchor-preview">{{ form.anchorText }}</blockquote>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { createNote, updateNote } from '@/api/notes'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  paperId: { type: Number, default: null },
  initial: { type: Object, default: null }
})

const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const form = ref({ title: '', content: '', anchorText: '', page: 1, coordinates: null })
const saving = ref(false)
const isEdit = computed(() => !!props.initial?.id)

watch(() => props.initial, (val) => {
  if (val) {
    form.value = {
      title: val.title || '',
      content: val.content || '',
      anchorText: val.anchorText || '',
      page: val.page || 1,
      coordinates: val.coordinates || null
    }
  } else {
    form.value = { title: '', content: '', anchorText: '', page: 1, coordinates: null }
  }
}, { immediate: true })

function onClose() {
  form.value = { title: '', content: '', anchorText: '', page: 1, coordinates: null }
}

async function save() {
  if (!form.value.title.trim()) {
    ElMessage.warning('请输入标题')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.value.title.trim(),
      content: form.value.content,
      page: form.value.page,
      coordinates: form.value.coordinates,
      anchorText: form.value.anchorText
    }
    const result = isEdit.value
        ? await updateNote(props.initial.id, payload)
        : await createNote(props.paperId, payload)
    ElMessage.success('已保存')
    emit('saved', result)
    visible.value = false
  } catch (e) {
    ElMessage.error('保存失败：' + (e.response?.data?.message || e.message))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.anchor-preview {
  margin: 0;
  padding: 8px 12px;
  background: var(--ra-bg);
  border-left: 3px solid var(--ra-link);
  font-size: 13px;
  color: var(--ra-text-secondary);
}
</style>
