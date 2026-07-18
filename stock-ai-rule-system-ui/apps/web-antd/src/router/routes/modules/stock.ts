import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    meta: {
      icon: 'lucide:activity',
      order: 0,
      title: '股票规则控制台',
    },
    name: 'StockConsole',
    path: '/stock',
    children: [
      {
        component: () => import('#/views/stock/signals/index.vue'),
        meta: {
          affixTab: true,
          icon: 'lucide:gauge',
          title: '信号看板',
        },
        name: 'StockSignals',
        path: '/stock/signals',
      },
      {
        component: () => import('#/views/stock/risk/center/index.vue'),
        meta: {
          icon: 'lucide:shield-alert',
          title: '风险中心',
        },
        name: 'StockRiskCenter',
        path: '/stock/risks',
      },
      {
        component: () => import('#/views/stock/detail/index.vue'),
        meta: {
          icon: 'lucide:search-check',
          title: '股票研究',
        },
        name: 'StockResearch',
        path: '/stock/research',
      },
      {
        component: () => import('#/views/stock/detail/index.vue'),
        meta: {
          hideInMenu: true,
          icon: 'lucide:search-check',
          title: '股票研究',
        },
        name: 'StockDetail',
        path: '/stock/research/:symbol',
      },
      {
        component: () => import('#/views/stock/rule-governance/index.vue'),
        meta: {
          icon: 'lucide:workflow',
          title: '规则治理',
        },
        name: 'StockRuleGovernance',
        path: '/stock/rule-governance',
      },
      {
        component: () => import('#/views/stock/backtests/index.vue'),
        meta: {
          icon: 'lucide:chart-no-axes-combined',
          title: '回测报告',
        },
        name: 'StockBacktests',
        path: '/stock/backtests',
      },
      {
        component: () => import('#/views/stock/ai-review/index.vue'),
        meta: {
          icon: 'lucide:brain-circuit',
          title: 'AI 复盘',
        },
        name: 'StockAiReview',
        path: '/stock/ai-review',
      },
      {
        component: () => import('#/views/stock/candidates/index.vue'),
        meta: {
          icon: 'lucide:git-pull-request-arrow',
          title: '候选规则',
        },
        name: 'StockCandidates',
        path: '/stock/candidates',
      },
      {
        component: () => import('#/views/stock/run-center/index.vue'),
        meta: {
          icon: 'lucide:calendar-clock',
          title: '运行中心',
        },
        name: 'StockRunCenter',
        path: '/stock/run-center',
      },
      {
        component: () => import('#/views/stock/operations/index.vue'),
        meta: {
          hideInMenu: true,
          icon: 'lucide:database-zap',
          title: '运营控制台',
        },
        name: 'StockOperations',
        path: '/stock/operations',
      },
      {
        component: () => import('#/views/stock/workflow/index.vue'),
        meta: {
          hideInMenu: true,
          icon: 'lucide:calendar-clock',
          title: '调度运行',
        },
        name: 'StockWorkflow',
        path: '/stock/workflow',
      },
      {
        component: () => import('#/views/stock/rules/index.vue'),
        meta: {
          hideInMenu: true,
          icon: 'lucide:workflow',
          title: '规则管理',
        },
        name: 'StockRules',
        path: '/stock/rules',
      },
      {
        component: () => import('#/views/stock/rule-versions/index.vue'),
        meta: {
          hideInMenu: true,
          icon: 'lucide:git-compare-arrows',
          title: '规则版本',
        },
        name: 'StockRuleVersions',
        path: '/stock/rules/:ruleCode/versions',
      },
    ],
  },
];

export default routes;
