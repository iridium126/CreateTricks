```json
{
  "title": "新增戏法",
  "icon": "trickster:amethyst_knot",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 4
}
```

机械动力：魔法工业注册了三个原创戏法。第一个让你花费魔力去*推动*动能机械；另外两个则是通往[咒法学之桥](^createmanaindustry:mana_industry/hexcasting_bridge)的大门。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:temporary_kinetic_stress|>

**动能应力戏法**——为任意动能机械临时施加应力与转速，如同刚刚接入一个动力源。应力大小为`|速度| × 4`；时长结束后机器恢复原状。消耗`manaPerStress × 应力 × 时长 × 2`点魔力，并返回目标位置。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:read_iota|>

**读取 Iota**——在 16 格范围内从含 Iota 的物品中读取咒法学 Iota，生成*Iota 片段*。槽位参数可选，缺省为另一只手。

;;;;;

<|trick@trickster:templates|trick-id=createmanaindustry:eval_iota|>

**执行 Iota**——执行[Iota 片段](^createmanaindustry:mana_industry/hexcasting_bridge)中存储的咒法学法术，其余参数作为其初始栈。在法术组构台内施放时消耗组构台存储的媒质；以玩家身份施放时消耗你物品栏中的媒质。完整机制见[咒法学之桥](^createmanaindustry:mana_industry/hexcasting_bridge)。
