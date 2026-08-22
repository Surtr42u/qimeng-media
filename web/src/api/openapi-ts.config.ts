// @hey-api/openapi-ts 生成配置（协议先行的 TS 客户端入口）
// 为什么放这里：web 根目录不在 sdk 任务的改动白名单内。
// 重新生成：make sdk（内部固定在 web/ 目录执行 npx openapi-ts -f 本文件，
// 下面的 input/output 路径因此相对 web/ 而不是本文件位置）
// 输出目录 generated/ 是生成物，禁止手改；协议变更先改 api/openapi.yaml 再重新生成。
import { defineConfig } from '@hey-api/openapi-ts';

export default defineConfig({
  input: '../api/openapi.yaml',
  output: 'src/api/generated',
  plugins: ['@hey-api/typescript', '@hey-api/sdk'],
});
