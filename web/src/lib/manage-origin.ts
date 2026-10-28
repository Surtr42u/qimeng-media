/** 只允许站内已知入口，刷新后仍能返回原工作区；不接受任意returnTo地址。 */
import { MAINTENANCE_FILES_PATH, MAINTENANCE_PATH } from './route-keys'

export function serviceUploadPath(path: string): string {
  return `${path}?from=service`
}

export function manageReturn(search: string) {
  return new URLSearchParams(search).get('from') === 'service'
    ? { path: MAINTENANCE_PATH, label: '服务与维护' }
    : { path: MAINTENANCE_FILES_PATH, label: '数据管理' }
}
