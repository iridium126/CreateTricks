```json
{
  "title": "动力法术核心",
  "icon": "createmanaindustry:kinetics_spell_core",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 2,
  "associated_items": ["createmanaindustry:kinetics_spell_core"]
}
```

**动力法术核心**是一种从机械动力的*齿轮链*汲取力量的[法术核心](^trickster:items/infrastructure/spell_core)。将其放入[模块化法术组构台](^trickster:items/infrastructure/modular_spell_construct)的四个角落槽之一，并将组构台接入 Bits 'n' Bobs 的**齿轮链**。

;;;;;

接入齿轮链后，组构台的法术执行上限为`链速 / 32`倍正常上限——链转得越快，组构台能执行的法术越多，下限为 1。没有齿轮链时核心完全惰性，组构台根本无法执行法术。

;;;;;

每个核心还会从网络中*索取*应力：每核心 **4 SU × 转速**，与任何其他动能负载一样经由齿轮链传导。拆毁组构台时，残余齿轮链会自动重建，并退还损失的链长。旋转的齿轮由核心自身渲染。
