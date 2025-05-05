<script setup>
import MenuIcon from "@/components/Icon/MenuIcon.vue";
import { computed } from 'vue'
import { ElSubMenu, ElMenuItem } from 'element-plus'

defineOptions({
  name: 'SubMenu'
})

const props = defineProps({
  item: {
    type: Object,
    required: true
  }
})

const hasChildren = computed(() => {
  return props.item?.children?.length > 0
})

</script>

<template>
  <!-- 判断当前菜单项是否有子菜单 -->
  <template v-if="hasChildren">
    <!-- 如果有子菜单，渲染 ElSubMenu 组件 -->
    <el-sub-menu :index="item?.path">
      <!-- 定义子菜单标题区域的内容 -->
      <template #title>
        <!-- 渲染菜单图标组件，显示菜单的图标 -->
        <MenuIcon :icon="item?.meta?.icon" />
        <!-- 显示菜单的标题文本 -->
        <span>{{ item.meta?.title }}</span>
      </template>
      <!-- 递归渲染子菜单 -->
      <SubMenu
        v-for="child in item?.children"
        :key="child.path"
        :item="child"
      />
    </el-sub-menu>
  </template>
  <!-- 如果没有子菜单，渲染 ElMenuItem 组件 -->
  <el-menu-item v-else :index="item.path">
    <!-- 渲染菜单图标组件，显示菜单的图标 -->
    <MenuIcon :icon="item.meta?.icon" />
    <!-- 定义菜单项标题区域的内容 -->
    <template #title>
      <!-- 显示菜单的标题文本 -->
      <span>{{ item?.meta?.title }}</span>
    </template>
  </el-menu-item>
</template>


<style scoped>
.el-icon {
  vertical-align: middle;
  margin-right: 5px;
  width: 24px;
  text-align: center;
}
/* 移除最后一项的底部边框 */
.el-menu-item:last-child {
  border-bottom: none !important;
}
</style>