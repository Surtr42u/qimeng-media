# FONTS - 外挂字幕随包字体

> 职责：记录随客户端分发的字幕字体来源、许可、SHA-256 与加载方式（唯一正文位置）。
> 字幕目录约定/语言/格式/编码规则见 [CAPABILITY_MAP](CAPABILITY_MAP.md)「外挂字幕」行与
> [browse API](api/browse.md)；交互约束见 [GUIDE_UI](GUIDE_UI.md)。

## 为什么随包

`.ass` 样式里写的字体名是 `Microsoft YaHei`（中文）与 `Arial`（英文）。Windows 有这两个字体，
Android / Linux 浏览器不一定有，会各自回落成不同家族——同一份字幕在三端行高、换行与字距不一致。
解决：把两份可再分发的 OFL 字体随包分发，用**家族名别名**接管这两个名字：

| 字体名（.ass 里写的） | 实际字体 | 许可 | 来源 |
|---|---|---|---|
| `Microsoft YaHei`（或中文名 `微软雅黑`） | Noto Sans SC Bold（中文黑体） | SIL OFL 1.1（Adobe，保留字体名 'Source'） | Google Fonts `ofl/notosanssc` 可变字体，`wght=700` 静态实例 |
| `Arial` | Liberation Sans Bold（与 Arial 度量兼容） | SIL OFL 1.1（Google / Red Hat，保留名 Liberation） | Debian `fonts-liberation` 2.1.5-3 包（上游 liberation-fonts 2.1.5） |

## 文件与 SHA-256

| 端 | 文件 | 字节 | SHA-256 |
|---|---|---|---|
| Web/桌面 | `web/public/fonts/NotoSansSC-Bold.woff2` | 4,314,472 | `86f9e62892b9cd362defcbfbf338e03f853ee0eb79d6ea57a1366ac21439751c` |
| Web/桌面 | `web/public/fonts/LiberationSans-Bold.woff2` | 148,580 | `727d6d4369b3bd672c3cf706f840b8dd5ca6c8e7e55a11ad6ae6b86e3cb238e6` |
| Android | `android/feature/detail/src/main/res/font/noto_sans_sc_bold.ttf` | 10,585,400 | `5684721daebea194a94c79b9a585ce4dd07296c85a503b0be068e6c524768464` |
| Android | `android/feature/detail/src/main/res/font/liberation_sans_bold.ttf` | 414,568 | `1b5f2da6f4cadce4c05b9ecebe3a6fcd374eb95ae443605e799f4c3287978939` |
| Web/桌面 | `web/public/fonts/OFL-NotoSansSC.txt` | 4,388 | `1c05c68c34f9708415aada51f17e1b0092d2cea709bf4a94cd38114f9e73d7d9` |
| Web/桌面 | `web/public/fonts/OFL-LiberationSans.txt` | 4,414 | `93fed46019c38bbe566b479d22148e2e8a1e85ada614accb0211c37b2c61c19b` |
| Android | `android/feature/detail/src/main/assets/fonts/OFL-*.txt` | 同上 | 同上（与 Web 端同一份许可文本） |

woff2 由同一 TTF 用 fontTools `woff2.compress` 转换（`fonttools 4.69.1` + `brotli`），
字形集合与 TTF 完全一致；两端因此只有容器格式差异，渲染字形相同。

## 加载方式

### Web / 桌面

- `web/src/styles/subtitle-fonts.css` 用 `@font-face` 把家族名 `Microsoft YaHei` / `微软雅黑` / `Arial`
  指向上述 woff2（仅 Bold 字重，`font-display: swap`）；
- 渲染 ASS 前 `lib/external-subtitles.ts` 的 `ensureSubtitleFonts()` 用
  `document.fonts.load('700 16px "Microsoft YaHei"')` / `"微软雅黑"` / `"Arial"` 预载，等 Promise 完成才
  `new ASS(...)`——assjs 在构造时按家族名量字体度量，字体未就绪会先按系统回落字体量宽，
  位置/行高整体跑偏；
- 家族名别名是文档级的：ArtPlayer 默认字体栈含 Arial / Microsoft YaHei，若不处理，播放器
  控件文字也会被换成随包粗体。`subtitle-fonts.css` 用 `.video-player-box .art-video-player`
  （两级类名压过 ArtPlayer 运行时注入的同级规则）把控件栈换成不含这两个名字的系统栈。
- 字体只在有外挂字幕播放时按需下载（4.3 MB），不进 PWA precache——离线时本来就无法从
  服务端取字幕，预缓存没有收益。

### Android

- 字体放在 `feature:detail` 的 `res/font/`（资源加载，不依赖 assets 压缩策略）；
- `SubtitleFonts.kt` 构造 typeface：API 29+ 用
  `Typeface.CustomFallbackBuilder(FontFamily(Liberation)) + fallback FontFamily(Noto)`，
  复刻 Web 的「拉丁→Liberation、汉字→Noto」分工；API 26–28 没有自定义回退链接口，
  统一用 Noto（含拉丁字形）；
- `BiliPlayerView` 把它作为 `CaptionStyleCompat.typeface` 传给 PlayerView 自带的
  `SubtitleView`（无样式字幕的默认字体；ASS 的颜色/粗体/字号 span 仍按文件生效）。

## 已知差异（不允许静默降级）

1. **无法按 Fontname 逐行分发**：Media3 1.11 的 SSA 解析器只保留颜色/粗斜体/下划线等
   span，不保留字体名，也没有按名注入字体的公开 API（libass 才有）。Android 因此是
   「视图级 typeface 覆盖」：英文也走 Liberation、中文走 Noto；与 Web 端按行分发相比，
   同一 cue 内中西文混排时字距可能有亚像素差异。这是当前版本的取舍，不是遗漏。
2. **字重固定 Bold**：`.ass` 里 `Bold=0` 的行在两端都会用到 Bold 文件（唯一字重）；浏览
   器/Android 的合成加粗不会额外叠加。
3. **字形覆盖**：Noto Sans SC 子集覆盖 31,036 字形（含常用汉字、假名、拉丁、全角标点），
   不含韩文音节与生僻扩展 B 区（如 𠮷）；缺字回退系统字体（终端设备可见差异）。
4. **字体未内嵌**（`.ass` 附带的字体附件）不解析——两端都不支持附件字体，只用随包两份。

## 再生成 / 升级

```sh
# Noto Sans SC Bold 静态实例（Google Fonts 可变字体）
python - <<'PY'
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
f = TTFont("NotoSansSC-VF.ttf")
instantiateVariableFont(f, {"wght": 700}, updateFontNames=True).save("NotoSansSC-Bold.ttf")
PY
# woff2
python - <<'PY'
from fontTools.ttLib.woff2 import compress
compress("NotoSansSC-Bold.ttf", "NotoSansSC-Bold.woff2")
PY
```

升级后：替换两端的字体文件与许可文本、重算本节 SHA-256、更新
[CAPABILITY_MAP](CAPABILITY_MAP.md) 的字体行，并在 CHANGELOG 记一笔。

## 许可证文本（SIL Open Font License 1.1）

以下为随包分发的 `OFL-NotoSansSC.txt` 全文（Noto Sans SC）；`OFL-LiberationSans.txt`
是同一许可正文，仅版权首段不同（首段已在上表来源注明）。两份文件随 `web/public/fonts/`
与 Android `assets/fonts/` 一起分发。

```text
Copyright 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'

This Font Software is licensed under the SIL Open Font License, Version 1.1.
This license is copied below, and is also available with a FAQ at:
https://scripts.sil.org/OFL


-----------------------------------------------------------
SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
-----------------------------------------------------------

PREAMBLE
The goals of the Open Font License (OFL) are to stimulate worldwide
development of collaborative font projects, to support the font creation
efforts of academic and linguistic communities, and to provide a free and
open framework in which fonts may be shared and improved in partnership
with others.

The OFL allows the licensed fonts to be used, studied, modified and
redistributed freely as long as they are not sold by themselves. The
fonts, including any derivative works, can be bundled, embedded, 
redistributed and/or sold with any software provided that any reserved
names are not used by derivative works. The fonts and derivatives,
however, cannot be released under any other type of license. The
requirement for fonts to remain under this license does not apply
to any document created using the fonts or their derivatives.

DEFINITIONS
"Font Software" refers to the set of files released by the Copyright
Holder(s) under this license and clearly marked as such. This may
include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the
copyright statement(s).

"Original Version" refers to the collection of Font Software components as
distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting,
or substituting -- in part or in whole -- any of the components of the
Original Version, by changing formats or by porting the Font Software to a
new environment.

"Author" refers to any designer, engineer, programmer, technical
writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS
Permission is hereby granted, free of charge, to any person obtaining
a copy of the Font Software, to use, study, copy, merge, embed, modify,
redistribute, and sell modified and unmodified copies of the Font
Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components,
in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled,
redistributed and/or sold with any software, provided that each copy
contains the above copyright notice and this license. These can be
included either as stand-alone text files, human-readable headers or
in the appropriate machine-readable metadata fields within text or
binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font
Name(s) unless explicit written permission is granted by the corresponding
Copyright Holder. This restriction only applies to the primary font name as
presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
Software shall not be used to promote, endorse or advertise any
Modified Version, except to acknowledge the contribution(s) of the
Copyright Holder(s) and the Author(s) or with their explicit written
permission.

5) The Font Software, modified or unmodified, in part or in whole,
must be distributed entirely under this license, and must not be
distributed under any other license. The requirement for fonts to
remain under this license does not apply to any document created
using the Font Software.

TERMINATION
This license becomes null and void if any of the above conditions are
not met.

DISCLAIMER
THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
OTHER DEALINGS IN THE FONT SOFTWARE.
```
