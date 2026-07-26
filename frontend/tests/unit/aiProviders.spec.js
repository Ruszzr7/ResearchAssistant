import { describe, expect, it } from 'vitest'
import {
  AI_PROVIDERS,
  channelDefinition,
  inferChannel,
  inferProvider,
  providerModels,
  shouldReplaceBaseUrl,
} from '../../src/config/aiProviders'

describe('ai provider configuration', () => {
  it('contains the six explicitly supported providers', () => {
    expect(AI_PROVIDERS.map(provider => provider.value)).toEqual([
      'kimi', 'deepseek', 'glm', 'minimax', 'mimo', 'openai',
    ])
  })

  it('infers legacy Kimi Coding settings', () => {
    expect(inferProvider('https://api.kimi.com/coding/v1', 'k3-256k')).toBe('kimi')
    expect(inferChannel('kimi', 'https://api.kimi.com/coding/v1')).toBe('coding')
  })

  it('selects provider channel defaults while keeping models user-editable', () => {
    expect(channelDefinition('glm', 'coding').baseUrl)
      .toBe('https://open.bigmodel.cn/api/coding/paas/v4')
    expect(providerModels('mimo', 'payg')).toContain('mimo-v2.5-pro')
    expect(providerModels('kimi', 'coding')).toContain('k3')
    expect(providerModels('kimi', 'platform')).toContain('kimi-k3')
  })

  it('replaces known defaults but preserves custom compatible gateways', () => {
    expect(shouldReplaceBaseUrl('https://api.openai.com/v1/')).toBe(true)
    expect(shouldReplaceBaseUrl('https://gateway.example.com/team/v1')).toBe(false)
  })
})
