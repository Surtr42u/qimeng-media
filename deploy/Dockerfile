# qimeng-media 服务端镜像（M5 部署三件套之一；fnOS 1.2 虚拟机 amd64 实测通过）
#
# 前置产物（开发机仓库根执行）:
#   1. make web-build          -> web/dist（SPA 静态产物）
#   2. make server-linux-amd64 -> build/linux/amd64/qimeng-server（纯 Go 静态二进制）
#
# 构建上下文目录须包含三个条目: qimeng-server / web-dist/ / config-docker.yaml
#   docker build -f deploy/Dockerfile -t qimeng-media:1.0 <上下文目录>
#
# 网络备注: Docker Hub 不可达时先从镜像源拉基础镜像，再把 FROM 换成完整引用
#   （fnOS 虚拟机实测: docker pull docker.m.daocloud.io/library/debian:bookworm-slim）。
FROM debian:bookworm-slim

# ffmpeg 为缩略图管线/扫描探测/上传视频探测的运行期依赖（ffprobe 同包）；
# apt 源换 USTC 镜像加速容器内构建（Debian 12 deb822 源文件路径）。
RUN sed -i 's|deb.debian.org|mirrors.ustc.edu.cn|g' /etc/apt/sources.list.d/debian.sources && \
    apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY qimeng-server /app/qimeng-server
COPY web-dist /app/web-dist
# 容器内兜底配置（生产安全基线）；部署差异走 QIMENG_* 环境变量覆盖
COPY config-docker.yaml /app/config.yaml
RUN chmod +x /app/qimeng-server

# 端口与 api/openapi.yaml servers 保持一致（协议宪法），改动须两处双同步
EXPOSE 8420
# 服务端私有数据（数据库/缩略图/回收站/备份）挂载点，compose 里落到宿主机目录
VOLUME ["/data"]
ENTRYPOINT ["/app/qimeng-server", "-config", "/app/config.yaml"]
