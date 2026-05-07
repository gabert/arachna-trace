<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router';
import StatusBar from './components/StatusBar.vue';
</script>

<template>
  <div class="app-shell app-dark">
    <header class="app-header">
      <RouterLink to="/" class="brand">DeepFlow</RouterLink>
      <nav>
        <RouterLink to="/sessions">Sessions</RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
    <StatusBar />
  </div>
</template>
