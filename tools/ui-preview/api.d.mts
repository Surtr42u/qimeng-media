/** 浏览器回归共享同一虚拟API；此声明只描述测试工具端口，不是SDK。 */
export function createPreviewApi(now?: number): (pathname: string, method?: string, params?: URLSearchParams, body?: Record<string, unknown>) => { status: number; body: unknown; headers: Record<string, string> }
export function reviewFixture(range?: string): Record<string, unknown>
