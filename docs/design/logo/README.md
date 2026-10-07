# PureBrowser Logo（已选定方向 B「下载即主角」）

2026-10-07 从 `artifacts/ui-exploration/logo-video-focus-v2/` 的四个概念中选定 **方向 B「下载即主角」**：
一支粗壮的向下下载箭头（宽矩形箭杆 + 短宽箭头），箭杆中央镂空白色向右播放三角，箭杆顶部
带一处不对称的浏览器标签台阶，下方一段与箭头留白分离的接收底线。一笔读出「下载 · 视频 · 浏览器」，
纯色 `#356DE8`，无渐变无描边，小尺寸可辨。探索草稿与其余三个方向（A/C/D）保留在 artifacts/，不入库为正式资产。

## 文件

- `B-download-video-source.png` — 已批准的源图（1024，蓝底白版之前的原始蓝on白），一切生成的唯一来源。
- `B-download-video-presentation.png` — 批准时的单色/深色/桌面预览卡（参考）。
- `B-download-video-mask.png` — 从源图提取的形状遮罩（脚本自动重建，勿手改）。
- `icon.png` — 512px 品牌图标（README / 展示用）。
- `generate_launcher_icons.py` — 生成脚本（见下）。

## 启动图标

- 自适应（API 26+）：`mipmap-anydpi-v26/ic_launcher{,_round}.xml` → 背景 `@drawable/ic_launcher_background`（`#356DE8` 纯色 shape）、前景/单色层 `drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_{foreground,monochrome}.png`（108dp×密度 = 108–432px，白标完全位于 66dp 安全区圆内，脚本有断言）。单色层供 Android 13 主题色图标。
- 传统位图（旧启动器回退）：`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png`（48–192px 圆角方形）与 `ic_launcher_round.png`（圆形裁切）。
- 前台服务通知小图标沿用 `R.drawable.ic_launcher_foreground`（ControlledDownloadService），随新图形自动一致。

## 重新生成

```sh
python3 docs/design/logo/generate_launcher_icons.py
```

脚本仅依赖 Pillow；从源图重新提取遮罩（复用探索期 `build_comparison.py` 的覆盖率算法），
重写上述全部图层与 XML，并断言尺寸、密度、品牌蓝与安全区。
