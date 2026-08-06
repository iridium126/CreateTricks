```json
{
  "title": "未完成晶结",
  "icon": "createmanaindustry:incomplete_emerald_knot",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 3,
  "associated_items": ["createmanaindustry:incomplete_emerald_knot", "createmanaindustry:incomplete_prismatic_knot", "createmanaindustry:incomplete_diamond_knot", "createmanaindustry:incomplete_echo_knot", "createmanaindustry:incomplete_astral_knot"]
}
```

[晶结](^trickster:items/mana/knots)可以完全由机器制造。将晶结的宝石与玻璃方块合成，即可得到**未完成晶结**——一个不含魔力的空容器，其蓝色进度条显示已灌入的魔力量。

;;;;;

**注液器**以**液态魔力**填充未完成晶结——1000 mB 含 `manaPerBucket` 点魔力（默认 2048）。每次注液操作都会累加魔力；当容器达到晶结的完整制造花费时，它会当场**转化为成品晶结**：

- 祖母绿——512 魔力
- 棱镜——8,192 魔力
- 钻石——8,192 魔力
- 回声——65,536 魔力
- 星界——524,288 魔力

;;;;;

成品晶结可在**动力冲压机**中*碎裂*，产出对应的[开裂晶结](^trickster:items/mana/cracked_knots)。整个流水线——部署、注液、冲压——全部由标准机械动力自动化完成；含液态魔力的晶结也可以直接经由流体管道充能或抽取。
