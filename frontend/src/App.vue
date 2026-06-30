<template>
  <div class="app-container">
    <el-container>
      <el-header class="app-header">
        <span class="app-title">Research Assistant</span>
        <el-menu
          :default-active="$route.path"
          mode="horizontal"
          router
          class="app-nav"
        >
          <el-menu-item index="/">论文库</el-menu-item>
          <el-menu-item index="/search">文献检索</el-menu-item>
          <el-menu-item index="/analysis">论文分析</el-menu-item>
          <el-menu-item index="/gap">Gap 分析</el-menu-item>
        </el-menu>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
// 根组件：顶部导航 + 路由出口。$route.path 直接用于菜单高亮。
</script>

<style>
body {
  margin: 0;
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', Arial, sans-serif;
}
.app-header {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
  padding: 0 20px;
  height: 56px;
}
.app-title {
  font-size: 18px;
  font-weight: 600;
  margin-right: 30px;
  white-space: nowrap;
}
.app-nav {
  flex: 1;
  border-bottom: none !important;
}
</style>
