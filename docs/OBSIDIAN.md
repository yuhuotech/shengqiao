# Obsidian 按真实录音日期倒排

声桥 0.3.0 的 Markdown 头部会写入如下属性：

```yaml
---
shengqiao_id: "稳定录音ID"
recorded_at: 2026-10-13T11:00:00
recorded_month: "202610"
source: xiaomi-recorder
---
```

`recorded_at` 是小米录音的创建时间（北京时间），不会在同步、改名或更新转写时变成当前时间。文件仍使用录音名，例如 `小米录音/202610/10.13客满沟通会.md`。

Obsidian 原生文件浏览器支持按文件名、文件创建时间和修改时间排序，但文件系统时间可能在 NAS 上传或同步至本地时重建，不能把它当作真实录音时间；写入 `recorded_at` 属性也不会自动改变左侧文件树的排序。

## 左侧月份目录和录音均倒排

可以安装并启用社区插件 [Custom File Explorer sorting](https://github.com/SebastianMC/obsidian-custom-sort)。月份目录按名称降序（例如 `202611`、`202610`），每个月的笔记按 `recorded_at` 降序。原始录音标题和月份目录名保留。

若 Obsidian 仓库根目录是 WebDAV 的 `/obsidian`，在仓库中新建 `sortspec.md`，使用：

```yaml
---
sorting-spec: |
  target-folder: 小米录音
  /folders
   > a-z
  /:files
   > a-z

  target-folder: 小米录音/*
  > a-z by-metadata: recorded_at
---
```

这里 `target-folder` 是 **Obsidian 仓库内路径**，不是 WebDAV 完整路径。仓库根目录不同时需要相应调整。启用插件后点击其排序图标应用配置；插件文档说明元数据变化后可能需重新启用排序或执行 `sort on`，不能承诺所有同步情形都实时刷新。以上配置按插件文档提供，尚未在用户自己的 Obsidian 仓库中验证。

## 不安装社区插件

使用内置 Bases 新建录音总览，过滤到「小米录音」目录，以 `recorded_at` 按新到旧排序，也可按 `recorded_month` 分组并将月份倒排。若属性被识别为文本，可在属性设置中将 `recorded_at` 设置为「日期与时间」。这能得到最新录音在上方的列表，但不会改变左侧文件树。

声桥目前生成 Markdown 属性，不自动安装 Obsidian 插件、修改 `.obsidian` 配置或创建 Bases。WebDAV 文件还需通过用户已有的同步方式进入 Obsidian 本地仓库；仅上传到 WebDAV 不代表 Obsidian 已读取。

依据：[Obsidian 文件浏览器](https://obsidian.md/help/plugins/file-explorer)、[属性与日期格式](https://obsidian.md/help/properties)、[Bases 排序与分组](https://obsidian.md/help/bases/views)、[插件元数据排序说明](https://github.com/SebastianMC/obsidian-custom-sort/blob/master/docs/advanced-README.md#example-14-grouping-and-sorting-by-metadata-value)。
