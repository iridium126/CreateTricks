```json
{
  "title": "组构台媒质存储",
  "icon": "createmanaindustry:liquid_media_bucket",
  "category": "createmanaindustry:mana_industry/hexcasting_bridge",
  "ordinal": 1,
  "required_advancements": ["hexcasting:root"],
  "associated_items": ["createmanaindustry:liquid_media_bucket"]
}
```

[法术组构台](^trickster:items/infrastructure/spell_construct)可以直接容纳咒法学的*媒质*。含媒质的物品——充能紫水晶、媒质之瓶等——插入组构台时会被吸收进其内部媒质存储，而非普通物品槽位；你嵌入的魔力晶结则照常提供戏法师魔力。

;;;;;

存储的媒质为*组构台自身*执行的咒法学施法供能：**执行 Iota**戏法消耗它来运行咒法学法术，而在组构台内施放的咒法学图案**执行戏法**也从同一池中抽取。插入创造解锁器可提供无限媒质。媒质随组构台数据持久化保存，因此组构台在区块卸载后仍保留燃料。

;;;;;

自动化很简单：漏斗与机械臂可将媒质物品送入组构台，而媒质之瓶作为[液态媒质](^createmanaindustry:mana_industry/hexcasting_bridge/construct_media)的缓冲储备，随时可转化为媒质——正是咒法学一侧为施法物品注液所用的同一液体。
