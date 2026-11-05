import type { Author } from '@/api/generated'

/** Same ordering as Android AuthorRows, including unknown fields and stable ties. */
export function compareAuthorAddedOrder(left: Author, right: Author): number {
  const order = (left.txtOrder ?? Number.MAX_SAFE_INTEGER) - (right.txtOrder ?? Number.MAX_SAFE_INTEGER)
  if (order !== 0) return order
  const created = (left.addedAtMillis ?? Number.MAX_SAFE_INTEGER) - (right.addedAtMillis ?? Number.MAX_SAFE_INTEGER)
  if (created !== 0) return created
  return (left.id ?? '') < (right.id ?? '') ? -1 : (left.id ?? '') > (right.id ?? '') ? 1 : 0
}
