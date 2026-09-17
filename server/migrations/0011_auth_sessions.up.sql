-- 0011_auth_sessions.up：多设备并发会话（token 挤兑修复）。
-- 旧模型"单用户单 token"：每次登录 UPDATE users.token_hash 覆盖唯一哈希，
-- 多端登录互踢（手机登录 = 电脑 401）。新模型把有效哈希搬进 auth_sessions：
-- 每次登录 INSERT 一条会话，多端并存；logout 按哈希吊销单条会话。
-- users.token_hash 自本迁移起弃用：NOT NULL 兼容照留（新代码只在建用户行时
-- 填占位，不再作为鉴权依据）；删除该列需走 expand-migrate-contract 两步迁移
-- （ADR-0011「只加不改」），暂无必要。

CREATE TABLE auth_sessions (
    -- 会话标识，服务端生成 UUID（与 created_at 共同决定裁剪顺序，见 PruneSessions）
    id           TEXT PRIMARY KEY,
    -- 所属用户（M1 单用户=users 首行 admin；结构按多用户设计，同 users 表口径）
    user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                        -- 用户删除级联清会话：身份没了会话无意义
    -- Bearer token 的 SHA-256 hex（与 users.token_hash 同格式，明文永不落库）。
    -- UNIQUE：logout 按请求 token 的哈希精确定位且仅删一条
    token_hash   TEXT NOT NULL UNIQUE,
    -- 设备标签：登录时 User-Agent 截断 128 字符存档，便于用户辨认
    -- "这是哪台设备的会话"（后续会话管理 UI 的展示源）；空 UA 存空串
    device_label TEXT NOT NULL DEFAULT '',
    created_at   TEXT NOT NULL           -- 格式见 0001 文件头统一约定
);

-- 按用户拉会话（启动 load、登录后裁剪都按 user_id 取全量）
CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);

-- 回填：把旧模型唯一哈希转成一条会话——升级后已登录设备不掉线
-- （users 空表 = 未 setup，SELECT 零行，INSERT 自然零行）。
-- 固定 id 'legacy-single-token' 与标签 'legacy' 标记其来源为迁移而非登录。
INSERT INTO auth_sessions (id, user_id, token_hash, device_label, created_at)
SELECT 'legacy-single-token', id, token_hash, 'legacy', created_at FROM users;
