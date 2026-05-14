import { createApp } from 'vue';
import PrimeVue from 'primevue/config';
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore — @primeuix/themes ships ESM without bundled .d.ts at top level
import Aura from '@primeuix/themes/aura';

import App from './App.vue';
import router from './router';

import 'primeicons/primeicons.css';
import './styles/app.css';

const app = createApp(App);
app.use(router);
app.use(PrimeVue, {
  theme: { preset: Aura, options: { darkModeSelector: '.app-dark' } }
});
app.mount('#app');
