<template>
  <RouterView v-slot="{ Component }">
    <KeepAlive :include="include">
      <component :is="Component" />
    </KeepAlive>
  </RouterView>
</template>

<script setup>
import { KeepAlive } from 'vue'
import { RouterView } from 'vue-router'

defineProps({
  include: { type: [String, RegExp, Array], default: () => [] },
})
</script>
