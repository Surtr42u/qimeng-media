import type { Author } from '@/api/generated'

/** Same ordering as Android AuthorRows, including unknown fields and stable ties. */
export function compareAuthorAddedOrder(left: Author, right: Author, descending = false): number {
  const group = Number(left.txtOrder == null) - Number(right.txtOrder == null)
  if (group !== 0) return group
  if (left.txtOrder == null) {
    const unknown = Number(left.addedAtMillis == null) - Number(right.addedAtMillis == null)
    if (unknown !== 0) return unknown
  }
  const direction = descending ? -1 : 1
  const order = (left.txtOrder ?? Number.MAX_SAFE_INTEGER) - (right.txtOrder ?? Number.MAX_SAFE_INTEGER)
  if (order !== 0) return direction * order
  const created = (left.addedAtMillis ?? Number.MAX_SAFE_INTEGER) - (right.addedAtMillis ?? Number.MAX_SAFE_INTEGER)
  if (created !== 0) return direction * created
  return direction * ((left.id ?? '') < (right.id ?? '') ? -1 : (left.id ?? '') > (right.id ?? '') ? 1 : 0)
}
