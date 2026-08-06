# 白鹭模型/动画精修指引(M2b,Blockbench 工作流)

> 适用对象:想自己精修白鹭模型与动画的开发者。
> 前提:本机网络无法安装 GeckoLib(官方/Modrinth maven 均不可达),模型与动画走 **vanilla 原版体系**:
> 模型 = Blockbench 可视化建模,导出 Java 类;动画 = 现有代码姿态(后续可升级原版关键帧 JSON)。

## 1. 现状:模型与动画是怎么实现的

- **模型**:`src/client/java/com/birdwatch/client/entity/HeronModel.java` 的 `createBodyLayer()`
  是纯代码拼几何(`MeshDefinition` / `PartDefinition` / `CubeListBuilder`),10 个部件,贴图 64×32。
- **动画**:同文件的 `setupAnim()` 按行为状态(IDLE/FORAGING/FLYING/SLEEPING/ALERTING)
  计算姿态(弧度),指数平滑收敛,叠加扇翅/啄食/行走周期分量。状态经 `SynchedEntityData`
  同步到客户端,渲染器 `extractRenderState()` 传入模型。
- **动画只依赖"骨骼名 + 枢轴位置"**,不依赖几何尺寸——所以换几何、换贴图都不影响动画逻辑,
  但改骨骼名/枢轴会改变姿态效果,需要重新微调。

## 2. 你需要保留的结构约定(重要)

### 2.1 骨骼名(10 个,必须同名)

| 骨骼名 | 含义 | 要求 |
|---|---|---|
| `body` | 身体 | 挂根,作为所有关节的父 |
| `neckLower` | 下颈 | **body 的子骨骼**,枢轴在颈根(身体前上端) |
| `neckUpper` | 上颈 | **neckLower 的子骨骼**(链式嵌套!),枢轴在下颈顶端 |
| `headPivot` | **头+喙的共枢轴(空骨骼)** | **neckUpper 的子骨骼**;`head` 与 `beak` 作为它的子部件,枢轴在 (0,0,0) |
| `head` | 头 | headPivot 的子部件 |
| `beak` | 喙 | headPivot 的子部件,向模型前方延伸 |
| `legLeft` / `legRight` | 左右腿 | body 的子骨骼,枢轴在髋部(不是脚底) |
| `wingLeft` / `wingRight` | 左右翅 | body 的子骨骼,枢轴在肩部,翼向身体后方延伸 |
| `tail` | 尾羽 | body 的子骨骼,枢轴在尾根 |

**必须嵌套,不能平铺**——旋转行为由 outliner 层级决定:旋转父骨骼时,其下所有子元素绕**父骨骼的枢轴**整体旋转;
子骨骼再旋转时绕**自己的枢轴**(它已被父骨骼带着走)弯折,链条由此保持连接。
特别是**颈链**(neckLower → neckUpper → headPivot):大角度姿态(觅食低头、睡眠折颈)下若用绝对坐标,
两节会在关节处脱节(现有代码就是平铺,小角度看不出,大角度会断开,集成时一并改)。

Blockbench 操作注意:
- outliner 里把骨骼**拖进**父骨骼下面即可建立嵌套;**编辑器里枢轴仍显示世界绝对坐标,这是正常的**——
  相对化在导出 Java 代码时自动完成(子枢轴 − 父枢轴),游戏里按相对偏移渲染,编辑器预览即游戏效果
- neckUpper 的枢轴摆在自己根部(与 neckLower 顶端的连接点);新建骨骼默认枢轴在原点,记得拖到关节上
- **旋转相关的一律用"骨骼(bone)"**,不要用"组(group)"(组只是容器,动画只驱动骨骼)

### 2.2 枢轴位置(可自由微调,但须在关节处)

现有枢轴参考(vanilla 坐标,地面 y=24,模型 +y 朝下;Blockbench 里 y 朝上,导出时自动转换,
你**不需要手算坐标**,Blockbench 会处理好):

| 骨骼 | 现有枢轴(vanilla) | 关节含义 |
|---|---|---|
| body | (0, 14, 1) | 身体中段 |
| neckLower | (0, 11, -2) | 颈根 |
| neckUpper | (0, 5.5, -3) | 下颈顶端 |
| headPivot | (0, 0, -4) | 头顶(头+喙共同旋转中心) |
| legLeft / legRight | (±1.5, 19, 2) | 髋部 |
| wingLeft / wingRight | (±3, 12.5, 1) | 肩部 |
| tail | (0, 12, 6) | 尾根 |

规则:枢轴放在**关节**上(脖子每段底部、肩膀、髋部、尾根),姿态会自然;改了位置之后
告诉我一声,姿态角可能需要小幅重新调校。

### 2.3 朝向(关键,错了整个动画会镜像)

- 游戏里实体正面 = 模型 **−Z** 方向 → **喙必须最终朝向 −Z**。
- Blockbench 视口里有 XYZ 坐标指示器(红=X、绿=Y、蓝=Z)。**喙朝向与蓝色箭头(Z 轴)相反的方向**。
- 如果建模时方向拿不准:先建一个小方块当喙,集成后进游戏 `/summon birdwatch:heron` 看一眼,
  反了的话我一行代码翻转,不阻塞。

### 2.4 贴图

- 画布 64×32(与 `textures/entity/heron.png` 一致)。
- Blockbench 里把部件摆好后:纹理面板 → 新建纹理 → **自动生成 UV 展开模板**,直接在上面画。
- 画完导出 PNG,替换 `src/main/resources/assets/birdwatch/textures/entity/heron.png`。

## 3. Blockbench 操作步骤

1. 安装 [Blockbench](https://www.blockbench.net)(4.x 即可;内置 **Java Entity** 插件)。
2. `文件 → 新建 → Java Entity`(若没有,`文件 → 插件` 里启用 "Java Entity")。
3. 项目设置:名称 `heron`,**分辨率 64×32**。
4. 按第 2 节约定建骨骼、摆几何、调枢轴。
5. 纹理面板生成 UV 模板 → 绘制 → 导出 PNG。
6. `文件 → 导出 → Java Entity…` 生成 `.java` 文件(里面是 `createBodyLayer()` 同款代码)。

## 4. 交付给我什么

- 导出的 Java 类文件(随便命名,我负责改类名/包名/方法名后替换进 `HeronModel`)
- 贴图 `heron.png`(64×32)
- (可选)改动说明:新增/删除骨骼、结构变化、枢轴大改

我拿到后:
1. 替换 `createBodyLayer()`,按你的骨骼名重映射姿态表
2. 编译 + `runClient` 起游戏,你 `/summon birdwatch:heron` 检查
3. 姿态角按新几何微调一轮(你看着游戏画面提意见,我改数值)

## 5. 动画:下一步(路线 B,模型定稿后再说)

模型 OK 之后,可以把动画也搬到编辑器:
- Blockbench Java Entity 插件的**动画面板**(底部时间轴)摆关键帧,导出**原版 animation JSON**
- 我在代码里用 26.2 的 `AnimationDefinition`/`AnimationState` 搭加载框架,替换现在的数学姿态
- 这一步需要我先验证 26.2 动画 JSON 加载 API,工作量小,但等模型定稿再做最划算

## 6. 常见问题

- **导出的 Java 类编译不过?** 正常——Blockbench 按老版本 API 生成的部分签名(如 `PartPose.offsetAndRotation`)
  可能漂移,交给我适配 26.2。
- **模型朝向反了?** 集成时翻转,不用重做模型。
- **姿态看起来别扭?** 枢轴没放在关节上,或告诉我具体哪几个动作,我调姿态表。
- **想做多段颈(3 节)?** 可以,但告诉我,姿态表要加一个 `neckMiddle` 骨骼的分配。
- **贴图能再画吗?** 随时重画重导,只有 PNG 换文件,模型不用动。

## 7. GeckoLib 骨骼绑定指引(2026-08-06 更新)

> 路线变更:用户自备 GeckoLib jar(maven 网络不可达),模型走 GeckoLib 格式。
> 核心概念:**绑定 = outliner 父子关系**;骨骼 = 旋转中心(动画只驱动骨骼),方块 = 形状。

### 7.1 步骤

1. 安装插件:`文件 → 插件` → 搜 **GeckoLib Animated Entity** → 安装
2. **备份工程**(转换会改动文件)
3. `文件 → 转换项目 → GeckoLib Animated Entity`(坐标与 UV 原样保留)
4. 建骨骼:选中方块 → 右键 → **Create Bone**;共 7 个骨骼
   (body / neckLower / neckUpper / head(含 head+beak 两方块) / legLeft / legRight / wingLeft / wingRight / tail)
5. 设枢轴:选中骨骼 → 左侧属性面板 **Pivot 字段** 输入坐标(见下表)
6. 组层级:outliner 里拖拽 —— neckUpper 拖进 neckLower、head 拖进 neckUpper、
   翅/腿/尾拖进 body;head 下挂 head 与 beak 两个方块
7. 验证:选中骨骼按 E 旋转 —— 父转整体绕父枢轴,子转只弯自己
8. 导出:`文件 → 导出 → GeckoLib Animated Entity` → `.geo.json` 交给开发者

### 7.2 白鹭枢轴坐标(Blockbench y 向上,模型空间)

| 骨骼 | 枢轴 | 备注 |
|---|---|---|
| body | (8, 8, 9) | 身体中心 |
| neckLower | (8, 9, 5) | 颈根 |
| neckUpper | (8, 13, 5) | 下颈顶端 |
| head | (8, 18, 5) | 颈顶端(原是 (7,18,3) 头前角,需改) |
| legLeft / legRight | (6.5, 6, 8.5) / (9.5, 6, 8.5) | 髋部 |
| wingLeft / wingRight | (6, 8, 8) / (10, 8, 8) | 肩部(原在体外,需改) |
| tail | (8, 8.5, 13) | 尾根 |

常见坑:枢轴留在 (0,0,0) 会"行星公转";方块没拖进骨骼还挂 root 下(骨骼转它不动);转换前不备份。
