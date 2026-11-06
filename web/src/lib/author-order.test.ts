import { describe, expect, it } from 'vitest'
import { compareAuthorAddedOrder } from './author-order'

describe('author addition order', () => {
  it('keeps unlisted and unknown dates after the TXT group in both directions', () => {
    const authors = [
      { id: 'manual', addedAtMillis: 20 },
      { id: 'second', txtOrder: 2, addedAtMillis: 1 },
      { id: 'unknown' },
      { id: 'first', txtOrder: 1, addedAtMillis: 999 },
    ]
    const ascending = [...authors].sort((left, right) => compareAuthorAddedOrder(left, right))
    expect(ascending.map(author => author.id)).toEqual(['first', 'second', 'manual', 'unknown'])
    expect([...authors].sort((left, right) => compareAuthorAddedOrder(left, right, true)).map(author => author.id)).toEqual(['second', 'first', 'manual', 'unknown'])
  })
})
