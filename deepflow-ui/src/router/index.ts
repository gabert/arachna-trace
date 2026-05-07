import type { RouteRecordRaw } from 'vue-router';
import { createRouter, createWebHistory } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/sessions'
  },
  {
    path: '/sessions',
    name: 'sessions',
    component: () => import('../views/SessionsView.vue')
  },
  {
    path: '/sessions/:sessionId',
    name: 'session-detail',
    component: () => import('../views/SessionDetailView.vue'),
    props: true
  },
  {
    path: '/objects/:objectId',
    name: 'object-history',
    component: () => import('../views/ObjectHistoryView.vue'),
    props: true
  }
];

export default createRouter({
  history: createWebHistory(),
  routes
});
