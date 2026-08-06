# 小鸟建模规格(麻雀 / 山雀,通用 M4b 小体型鸟)

> 2026-08-07 定稿。与白鹭模型的差异:**无颈** —— 头直接坐身上。
> 适用范围:麻雀、山雀、猫头鹰、喜鹊、家燕、云雀等小体型鸟;白鹭/丹顶鹤等长颈鸟保留两段颈。

## 骨架约定(与白鹭的唯一差异)

**去掉 `neckLower` / `neckUpper` 两块骨骼**;`head` 直接坐 `body` 顶,
枢轴设在**头底**(= 原颈椎顶位置),低头动作由 head 单独完成。
其余骨骼名与白鹭完全一致:`body / legLeft / legRight / head / beak / tail / wingLeft / wingRight`,
全部挂根级。

动画 JSON 中不带 `neckLower/neckUpper` 轨道(无颈版动画由 tools 脚本转换,见下)。

## 麻雀尺寸表(16 单位网格,站姿脚底 y=0,总高 ≈ 8)

| 骨骼 | 尺寸 宽×高×长 | 参考 from→to | 枢轴 pivot | 语义 |
|---|---|---|---|---|
| body | 4×3×6 | [-2,2,-2]→[2,5,4] | [0, 4, 1] | 身体中心 |
| legLeft | 1×2×1 | [-2,0,0]→[-1,2,1] | [-1.5, 2, 0.5] | 腿顶=髋 |
| legRight | 1×2×1 | [1,0,0]→[2,2,1] | [1.5, 2, 0.5] | 腿顶=髋 |
| head | 3×3×3 | [-1.5,5,-4]→[1.5,8,-1] | [-1, 5, -2.5] | 头底=颈椎顶 |
| beak | 1×1×2 | [-0.5,6,-6]→[0.5,7,-4] | [-1.5, 6, -4] | 嘴根 |
| tail | 2×1×3 | [-1,4.5,4]→[1,5.5,7] | [0, 4.5, 4] | 尾基部 |
| wingLeft | 1×2×4 | [-3,3,-1]→[-2,5,3] | [-2, 5, 0] | 肩关节 |
| wingRight | 1×2×4 | [2,3,-1]→[3,5,3] | [2, 5, 0] | 肩关节 |

体型:圆胖短腿、嘴短钝、尾中等。beak 位于头前部下缘(嘴根对齐 head 前下端)。

## 山雀尺寸表(总高 ≈ 7.5,比麻雀小一号、头大尾长)

| 骨骼 | 尺寸 宽×高×长 | 参考 from→to | 枢轴 pivot |
|---|---|---|---|
| body | 3×3×5 | [-1.5,1.5,-2]→[1.5,4.5,3] | [0, 3.5, 1] |
| legLeft | 1×2×1 | [-1.5,0,0]→[-0.5,2,1] | [-1, 2, 0.5] |
| legRight | 1×2×1 | [0.5,0,0]→[1.5,2,1] | [1, 2, 0.5] |
| head | 3×3×3 | [-1.5,4.5,-4]→[1.5,7.5,-1] | [-1, 4.5, -2.5] |
| beak | 1×1×2 | [-0.5,5.5,-6]→[0.5,6.5,-4] | [-1.5, 5.5, -4] |
| tail | 2×1×4 | [-1,4,3]→[1,5,7] | [0, 4, 4] |
| wingLeft | 1×2×4 | [-2.5,2.5,-1]→[-1.5,4.5,3] | [-2, 4.5, 0] |
| wingRight | 1×2×4 | [1.5,2.5,-1]→[2.5,4.5,3] | [2, 4.5, 0] |

体型:瘦长、头圆大、尾长(长度 4)、嘴短小。

## 动画(7 个,键名 `animation.<物种>.*`)

| 键 | Java 状态 | 动的骨骼(无颈版) | 说明 |
|---|---|---|---|
| idle | IDLE 静止 | head / body | 呼吸 + 偶张望(头小幅转) |
| walk | IDLE 且移动 | head / body / legLeft / legRight | 双脚交替 |
| forage | FORAGING | head / body / legLeft / legRight / tail | **整身上身前倾低头啄食**(无颈,由 body 前倾 + head 下压实现) |
| alert | ALERTING | head / body | 挺立警戒(头转幅度大,可带 Y 转) |
| fly_takeoff | TAKEOFFING | head / body / legLeft / legRight / wingLeft / wingRight | 蹬地扑翅 |
| fly | FLYING | head / body / legLeft / legRight / wingLeft / wingRight | 持续扇翅 |
| sleep | SLEEPING | head / body(position) / legLeft / legRight / wingLeft | 缩头、身下沉、收翅 |

**制作方式(用户手做,2026-08-07 定稿)**:每种鸟单独制作,不做脚本转换。
行为参数(受惊灵敏度、觅食速度/频率)在 SpeciesRegistry 按物种配置,动画幅度/节奏配套微调:
- 删 `neckLower/neckUpper` 轨道(无颈)
- forage 由 body 前倾(-20°~-30°)+ head 下压(至 -80°~-100°)完成低头啄食
- 建议参考白鹭动画的 keyframe 节奏起步,再按物种手感调快调慢

## 资源路径

| 文件 | 麻雀 | 山雀 |
|---|---|---|
| 模型 geckolib/models/ | sparrow.geo.json | tit.geo.json |
| 动画 geckolib/animations/ | sparrow.animation.json | tit.animation.json |
| 贴图 textures/entity/ | sparrow.png | tit.png |

贴图:32×32(可 64×64)。麻雀:栗顶 + 白颊 + 灰腹喉黑点;山雀:黑头白颊 + 黄腹 + 黑腹线。

## 定稿后收尾(Claude 做)

1. SpeciesRegistry.java:麻雀/山雀 assetPrefix "little_egret" → "sparrow"/"tit"
2. 游戏实测
