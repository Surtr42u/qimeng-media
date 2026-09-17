-- 0011_auth_sessions.down：回滚多会话模型（删会话表）。
-- 注意：0011 之后的登录只写 auth_sessions、不再更新 users.token_hash，
-- down 回到单 token 语义后最后一次登录的设备可能需要重新登录
-- （schema 可逆 ≠ 行为无损；golang-migrate 的 down 本就要求配合旧版应用）。
DROP TABLE auth_sessions;
