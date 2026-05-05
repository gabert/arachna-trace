<script setup>
import { RouterLink, RouterView } from 'vue-router';
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
  </div>
</template>
