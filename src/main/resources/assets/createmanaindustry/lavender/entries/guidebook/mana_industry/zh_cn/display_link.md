```json
{
  "title": "显示设备参数",
  "icon": "create:display_link",
  "category": "createmanaindustry:mana_industry",
  "ordinal": 5,
  "associated_items": ["create:display_link"]
}
```

朝向[法术组构台](^trickster:items/infrastructure/spell_construct)的机械动力**显示设备**会将其文本直接写入组构台的*法术参数*——前八行成为第一至第八个参数。对于[模块化法术组构台](^trickster:items/infrastructure/modular_spell_construct)，四个执行槽各拥有独立的八行参数区。

;;;;;

存储的文本会在每 tick 写入执行中法术的参数，以*字符串片段*覆盖——缺失的行以*虚空片段*填充，因此读取参数的法术始终看到当前的显示状态。这使平凡的显示设备成为运行中法术的*实时配置面板*：切换数字、标签或整段字符串，而无需触碰法术本身。

;;;;;

与[动力法术核心](^createmanaindustry:mana_industry/kinetics_spell_core)配合：由齿轮链驱动的模块化组构台可以持续重读参数，让一排显示设备驱动一座全自动法术工厂。
