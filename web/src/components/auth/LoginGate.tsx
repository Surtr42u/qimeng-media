import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Copy, KeyRound, ShieldCheck } from 'lucide-react'
import { useAuthLogin, useAuthSetup } from '@/hooks/use-session'
import { setToken } from '@/lib/api-client'

/** 密码最短长度：与 M1 验收页一致（server/internal/httpapi/static/index.html 校验 >= 8） */
const MIN_PASSWORD_LENGTH = 8

/** 登录面板提示文案 */
const HINT_INITIALIZED = '系统已初始化过，请输入管理密码登录'
const HINT_LOGIN_FAILED = '密码错误，请重试'
const HINT_SETUP_FAILED = '初始化失败，请重试'
const HINT_COPIED = 'token 已复制，请妥善保存（仅此一次明文展示）'
const TOKEN_NOT_SHOWN = 'token 未返回，请查看服务端日志'

type Mode = 'setup' | 'login'

/**
 * 登录/首次初始化二合一面板。
 *
 * 二合一的原因（事实依据）：首次部署没有任何凭据，必须先 POST /auth/setup
 * 先导；已初始化（409 ALREADY_SETUP）则只有 token 可验证。两件事在一个
 * 面板里按模式切换，用户不需要理解协议细节。
 *
 * setup 成功如何处理 token：协议关键约束"token 仅 setup 响应明文一次"——
 * 成功后面板展示 token（可复制）+ 用户点"进入应用"才继续，避免用户
 * 未备份 token 就悄无声息地失去找回途径（M1 验收页直接进主界面是简化行为）。
 *
 * 登录机制说明：已初始化后走 POST /auth/login 用密码换新 token（单用户
 * 单 token 模型，登录会重铸 token 使旧 token 失效）——不再要求用户粘贴
 * 当年的 setup token（换设备/清缓存后无从找回）。/auth/verify 仅由
 * AuthGate 启动校验使用，请求体为空、token 走 Authorization 头。
 */
export function LoginGate() {
  const [mode, setMode] = useState<Mode>('setup')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  /** setup 成功后保存的一次性 token（明文展示阶段） */
  const [issuedToken, setIssuedToken] = useState<string | null>(null)

  const setup = useAuthSetup()
  const login = useAuthLogin()

  const handleSetup = (event: React.FormEvent) => {
    event.preventDefault()
    if (password.length < MIN_PASSWORD_LENGTH) {
      toast.warning(`密码至少 ${MIN_PASSWORD_LENGTH} 位`)
      return
    }
    if (password !== confirm) {
      toast.warning('两次输入的密码不一致')
      return
    }
    setup.mutate(password, {
      onSuccess: (result) => {
        const token = result.token
        if (!token) {
          toast.error(TOKEN_NOT_SHOWN)
          return
        }
        setIssuedToken(token)
      },
      // 409 = 已初始化：切 verify 模式（协议 PostApiV1AuthSetupErrors.409）
      onError: (error) => {
        if (is409(error)) {
          setMode('login')
          toast.info(HINT_INITIALIZED)
        } else {
          toast.error(HINT_SETUP_FAILED)
        }
      },
    })
  }

  const handleLogin = (event: React.FormEvent) => {
    event.preventDefault()
    if (!password) {
      toast.warning('请输入管理密码')
      return
    }
    login.mutate(password, {
      onSuccess: (result) => {
        const token = result.token
        if (!token) {
          toast.error(TOKEN_NOT_SHOWN)
          return
        }
        // token 落 store（localStorage 持久），之后无需再输密码
        setToken(token)
      },
      onError: () => toast.error(HINT_LOGIN_FAILED),
      // onSuccess 无需其他处理：store 更新会触发 AuthGate 重渲染放行
    })
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-[var(--qm-space-4)] bg-background px-6 text-foreground">
      <div className="flex items-center gap-[var(--qm-space-2)]">
        <ShieldCheck className="size-6 text-[var(--qm-primary)]" />
        <h1 className="text-xl font-bold">绮梦影库</h1>
      </div>

      {issuedToken ? (
        // setup 成功：一次性展示 token（协议承诺仅此一次明文）
        <div className="w-full max-w-md rounded-lg border bg-[var(--qm-surface)] p-6 shadow-sm">
          <p className="mb-2 text-sm text-[var(--qm-text-muted)]">
            初始化成功。以下 token 仅显示一次，请妥善保存：
          </p>
          <code className="block break-all rounded-md bg-[var(--qm-chip-bg)] p-3 text-sm">
            {issuedToken}
          </code>
          <div className="mt-[var(--qm-space-3)] flex gap-2">
            <Button
              type="button"
              variant="outline"
              className="flex-1"
              onClick={() => {
                void navigator.clipboard.writeText(issuedToken)
                toast.success(HINT_COPIED)
              }}
            >
              <Copy className="size-4" /> 复制
            </Button>
            <Button className="flex-1" onClick={() => setToken(issuedToken)}>
              进入应用
            </Button>
          </div>
        </div>
      ) : (
        <form
          onSubmit={mode === 'setup' ? handleSetup : handleLogin}
          className="flex w-full max-w-md flex-col gap-[var(--qm-space-3)] rounded-lg border bg-[var(--qm-surface)] p-6 shadow-sm"
        >
          <div className="flex items-center gap-2">
            <KeyRound className="size-4 text-[var(--qm-text-muted)]" />
            <h2 className="text-sm font-semibold">
              {mode === 'setup' ? '首次初始化：设置管理密码' : '登录'}
            </h2>
          </div>

          {mode === 'setup' ? (
            <>
              <Input
                type="password"
                placeholder={`管理密码（至少 ${MIN_PASSWORD_LENGTH} 位）`}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="new-password"
                autoFocus
              />
              <Input
                type="password"
                placeholder="确认密码"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                autoComplete="new-password"
              />
              <Button type="submit" disabled={setup.isPending}>
                {setup.isPending ? '初始化中…' : '初始化并生成 token'}
              </Button>
            </>
          ) : (
            <>
              <Input
                type="password"
                placeholder="管理密码"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                autoFocus
              />
              <Button type="submit" disabled={login.isPending}>
                {login.isPending ? '登录中…' : '登录'}
              </Button>
            </>
          )}
        </form>
      )}
    </div>
  )
}

/** 判断后端错误是否为 409（已初始化）；unknown 错误类型下按结构字段判断 */
function is409(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'status' in error &&
    (error as { status?: number }).status === 409
  )
}
