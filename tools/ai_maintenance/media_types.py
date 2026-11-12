"""执行端文件分类；扫描和编号共用扩展名表，不改变服务端 MIME 分类。"""
from pathlib import PurePath

IMAGE_EXTENSIONS = {
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".tif", ".tiff", ".avif", ".heic",
}
VIDEO_EXTENSIONS = {
    ".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".wmv", ".flv", ".mpg", ".mpeg", ".ts",
}


def numbering_category(name: str) -> str:
    extension = PurePath(name).suffix.lower()
    if extension in VIDEO_EXTENSIONS:
        return "video"
    if extension in IMAGE_EXTENSIONS:
        return "image"
    return "other"
