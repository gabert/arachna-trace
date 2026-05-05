import { createRouter, createWebHistory } from 'vue-router';

const routes = [
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
    path: '/sessions/:sessionId/calls/:callId',
    name: 'call-detail',
    component: () => import('../views/CallDetailView.vue'),
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
