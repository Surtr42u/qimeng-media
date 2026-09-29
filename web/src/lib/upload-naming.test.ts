import { describe, expect, it } from 'vitest'
import { baseNameOf, composeUploadName, extensionOf } from './upload-naming'

describe('extensionOf / baseNameOf（扩展名锁定口径）', () => {
  it('常规文件名拆出基名与含点扩展名', () => {
    expect(extensionOf('作品 01.jpg')).toBe('.jpg')
    expect(baseNameOf('作品 01.jpg')).toBe('作品 01')
  })

  it('无扩展名：扩展名空串、整体为基名', () => {
    expect(extensionOf('无名')).toBe('')
    expect(baseNameOf('无名')).toBe('无名')
  })

  it('点前缀隐藏文件：整体为基名、无扩展名（idx<=0 口径）', () => {
    expect(extensionOf('.hidden')).toBe('')
    expect(baseNameOf('.hidden')).toBe('.hidden')
  })

  it('多点文件名取最后一段为扩展名', () => {
    expect(extensionOf('a.b.mp4')).toBe('.mp4')
    expect(baseNameOf('a.b.mp4')).toBe('a.b')
  })
})

describe('composeUploadName（基名 + 锁定扩展名拼装）', () => {
  it('基名与扩展名直接拼接', () => {
    expect(composeUploadName('名 13', '.png')).toBe('名 13.png')
  })

  it('基名 trim 后为空返回空串（调用方回退原展示名）', () => {
    expect(composeUploadName('   ', '.png')).toBe('')
    expect(composeUploadName('', '.png')).toBe('')
  })

  it('扩展名为空只取基名', () => {
    expect(composeUploadName('名 13', '')).toBe('名 13')
  })
})
