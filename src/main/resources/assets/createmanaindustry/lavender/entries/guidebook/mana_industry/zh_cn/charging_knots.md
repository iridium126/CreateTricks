```json
{
  "title": "动力注魔台",
  "icon": "createmanaindustry:kinetic_mana_generator",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 1,
  "associated_items": ["createmanaindustry:kinetic_mana_generator"]
}
```

**动力注魔台**将机械动力的*应力*转化为充能的[晶结](^trickster:items/mana/knots)。将其正面朝向嵌在[法术组构台](^trickster:items/infrastructure/spell_construct)或[充能阵列](^trickster:items/mana/charging_array)中的晶结，提供动力后，魔力便每 tick 流入晶结。

;;;;;

滚动槽用于设定机器要求的*每转速应力*——从 4 到 256，默认 4。每 tick 它消耗`应力 × 转速`，并转化为`应力消耗 × manaPerStress`点魔力（默认 0.001），由充能阵列中的所有晶结平分。只充**传统**魔力类型；已满或无限的晶结不会被动用，过应力的机器则停机。

;;;;;

戴上护目镜可在当前转速下查看所需应力。由于机器只供给朝向面上的物品，自动化手臂与漏斗可以持续供给新晶结——在充能阵列或组构台上建立的**机械臂**交互点可直接插入与取出晶结。
