```json
{
  "title": "Iota 之桥",
  "icon": "trickster:amethyst_knot",
  "category": "createmanaindustry:mana_industry/hexcasting_bridge",
  "ordinal": 0,
  "required_advancements": ["hexcasting:root"],
  "associated_items": ["createmanaindustry:incomplete_cypher"]
}
```

咒法学以*Iota*存储数据；戏法师则使用*片段*。这座桥在两大体系之间无损双向翻译。在戏法师一侧，**Iota 片段**（`createmanaindustry:iota`）封装完整的咒法学 Iota，序列化为 NBT 并 gzip 压缩——传输中不丢失任何信息，其字形显示也与咒法学完全一致。

;;;;;

在咒法学一侧，**戏法 Iota**封装完整的戏法师*法术树*，以戏法师自有的压缩法术格式序列化。两者均无损承载内容，因此法术可以旅行：读取为戏法 Iota，交给戏法师，被修改后送回，再如同从未离开般执行。

;;;;;

两个入口互为镜像：

- **戏法师 → 咒法学**：[读取 Iota](^createmanaindustry:mana_industry/tricks)从手持物品中取出 Iota；**执行 Iota**运行其中的咒法学法术。
- **咒法学 → 戏法师**：*读取戏法师法术*图案（`qqqqqa`）将手持晶结中的法术读入戏法 Iota；*执行戏法*（`wdwewawqwqw`）运行它——即使在法阵内也可以，通过石板结抽取戏法师魔力。

两个图案均记载于咒法学手册的*机械动力：魔法工业 → 戏法师的艺术*章节。
