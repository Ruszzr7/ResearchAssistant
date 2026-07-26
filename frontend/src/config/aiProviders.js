export const AI_PROVIDERS = [
  {
    value: 'kimi',
    label: 'Kimi',
    channels: [
      {
        value: 'coding',
        label: 'Kimi Coding',
        baseUrl: 'https://api.kimi.com/coding/v1',
        models: ['k3', 'k3-256k', 'kimi-for-coding', 'kimi-for-coding-highspeed'],
      },
      {
        value: 'platform',
        label: 'Kimi 开放平台',
        baseUrl: 'https://api.moonshot.cn/v1',
        models: ['kimi-k3', 'kimi-k2.6', 'kimi-k2.5'],
      },
    ],
  },
  {
    value: 'deepseek',
    label: 'DeepSeek',
    channels: [{ value: 'default', label: '标准 API', baseUrl: 'https://api.deepseek.com/v1' }],
    models: ['deepseek-chat', 'deepseek-reasoner'],
  },
  {
    value: 'glm',
    label: 'GLM',
    channels: [
      { value: 'standard', label: '通用 API', baseUrl: 'https://open.bigmodel.cn/api/paas/v4' },
      { value: 'coding', label: 'Coding 套餐', baseUrl: 'https://open.bigmodel.cn/api/coding/paas/v4' },
    ],
    models: ['glm-5.2', 'glm-4.5-air', 'glm-4.6v'],
  },
  {
    value: 'minimax',
    label: 'MiniMax',
    channels: [
      { value: 'payg', label: '按量付费', baseUrl: 'https://api.minimaxi.com/v1' },
      { value: 'token_plan', label: 'Token Plan', baseUrl: 'https://api.minimaxi.com/v1' },
    ],
    models: ['MiniMax-M2.7', 'MiniMax-M2.7-highspeed', 'MiniMax-M2.5'],
  },
  {
    value: 'mimo',
    label: 'MiMo',
    channels: [
      { value: 'payg', label: '按量付费', baseUrl: 'https://api.xiaomimimo.com/v1' },
      { value: 'token_plan', label: 'Token Plan', baseUrl: 'https://token-plan-cn.xiaomimimo.com/v1' },
    ],
    models: ['mimo-v2.5-pro', 'mimo-v2.5'],
  },
  {
    value: 'openai',
    label: 'OpenAI',
    channels: [{ value: 'default', label: '标准 API', baseUrl: 'https://api.openai.com/v1' }],
    models: ['gpt-5.4', 'gpt-5.4-mini', 'gpt-4.1'],
  },
]

export const ALL_PROVIDER_BASE_URLS = AI_PROVIDERS
  .flatMap(provider => provider.channels.map(channel => channel.baseUrl))

export function providerDefinition(value) {
  return AI_PROVIDERS.find(provider => provider.value === value) || AI_PROVIDERS[0]
}

export function providerChannels(value) {
  return providerDefinition(value).channels
}

export function providerModels(value, channel) {
  const provider = providerDefinition(value)
  return channelDefinition(value, channel).models || provider.models || []
}

export function channelDefinition(provider, channel) {
  const channels = providerChannels(provider)
  return channels.find(item => item.value === channel) || channels[0]
}

export function inferProvider(baseUrl = '', model = '') {
  const base = String(baseUrl).toLowerCase()
  const name = String(model).toLowerCase()
  if (base.includes('kimi.com') || base.includes('moonshot.') || name.includes('kimi')
      || name === 'k3' || name.startsWith('k3-')) return 'kimi'
  if (base.includes('deepseek.') || name.startsWith('deepseek')) return 'deepseek'
  if (base.includes('bigmodel.') || base.includes('api.z.ai') || name.startsWith('glm')) return 'glm'
  if (base.includes('minimax') || name.startsWith('minimax')) return 'minimax'
  if (base.includes('xiaomimimo') || name.startsWith('mimo')) return 'mimo'
  return 'openai'
}

export function inferChannel(provider, baseUrl = '') {
  const base = String(baseUrl).toLowerCase()
  if (provider === 'kimi') return base.includes('/coding/') ? 'coding' : 'platform'
  if (provider === 'glm') return base.includes('/coding/') ? 'coding' : 'standard'
  if (provider === 'mimo') return base.includes('token-plan') ? 'token_plan' : 'payg'
  if (provider === 'minimax') return 'payg'
  return 'default'
}

export function shouldReplaceBaseUrl(current) {
  return !String(current || '').trim()
    || ALL_PROVIDER_BASE_URLS.includes(String(current).replace(/\/+$/, ''))
}
