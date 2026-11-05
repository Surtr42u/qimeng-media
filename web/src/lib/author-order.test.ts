import { describe, expect, it } from 'vitest'
import { compareAuthorAddedOrder } from './author-order'

describe('author addition order', () => {
  it('uses TXT order before timestamps and reverses the complete order', () => {
    const authors = [
      { id: 'manual', addedAtMillis: 20 },
      { id: 'second', txtOrder: 2, addedAtMillis: 1 },
      { id: 'unknown' },
      { id: 'first', txtOrder: 1, addedAtMillis: 999 },
    ]
    const ascending = [...authors].sort(compareAuthorAddedOrder)
    expect(ascending.map(author => author.id)).toEqual(['first', 'second', 'manual', 'unknown'])
    expect([...authors].sort((left, right) => compareAuthorAddedOrder(right, left))).toEqual(ascending.reverse())
  })
})
