package com.birdwatch.bird;

import com.birdwatch.BirdWatchMod;
import com.birdwatch.entity.BirdEntity;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.resources.ResourceKey;

import java.util.List;

/**
 * 物种参数表(M4a:数据驱动重构核心)。
 *
 * 每个物种一条记录,实体 AI / 属性 / 刷新 / 音效 / 美术资源全部由此驱动;
 * 新增物种 = 新增一条记录 + 一个薄实体类 + 资源(lang / 成就 / 美术)。
 * 实例见 {@link SpeciesRegistry}。
 *
 * @param id              物种 id:实体注册 id、图鉴条目 key、成就触发器参数、lang key 前缀
 * @param directoryName   照片归档目录(zh 主语言,如「白鹭」)
 * @param entityClass     实体类(构造器约定 (EntityType, Level),ModEntities 反射创建)
 * @param habitat         栖息类型:驱动 Goal 组合与行为集
 * @param diurnal         昼夜节律:true = 白天活跃夜晚栖息;false = 反相(猫头鹰)
 * @param width           碰撞箱宽
 * @param height          碰撞箱高
 * @param maxHealth       生命
 * @param movementSpeed   地面移动速度
 * @param flyingSpeed     飞行速度
 * @param followRange     寻路目标跟随范围
 * @param scareBase       惊扰基准距离(图鉴文案 / 文档用;M5 迷彩系数乘此值)
 * @param scareStill      静立惊扰距离
 * @param scareSneak      潜行惊扰距离
 * @param scareWalk       行走惊扰距离
 * @param scareRun        奔跑惊扰距离
 * @param waterRange      湿地专属:寻水采样半径(格)
 * @param waterFlyThreshold 湿地专属:距水超过此值强制回水(格)
 * @param strollFlyChanceNearWater 湿地闲逛:近水时用飞的概率;非湿地:闲逛用飞的概率
 * @param strollFlyChanceExplore   湿地闲逛:80 格内无水探索飞行的概率;非湿地:同义
 * @param strollFlyMinDist 闲逛飞行距离下限(湿地探索 / 非湿地短途跳跃共用)
 * @param strollFlyMaxDist 闲逛飞行距离上限
 * @param forageNearWater  觅食目标是否要求水边(湿地觅食)
 * @param ambient          鸣叫(听声辨位音源)
 * @param scared           受惊叫(起飞时)
 * @param hurt             受伤
 * @param death            死亡
 * @param flap             扇翅(飞行中周期性)
 * @param assetPrefix      美术资源前缀:geckolib/{models,animations}/<前缀>.json 与
 *                         textures/entity/<前缀>.png;美术未出稿前可指向其他物种资源占位
 */
public record BirdSpecies(
	String id,
	String directoryName,
	Class<? extends BirdEntity> entityClass,
	Habitat habitat,
	boolean diurnal,
	float width,
	float height,
	double maxHealth,
	double movementSpeed,
	double flyingSpeed,
	double followRange,
	double scareBase,
	double scareStill,
	double scareSneak,
	double scareWalk,
	double scareRun,
	double waterRange,
	double waterFlyThreshold,
	float strollFlyChanceNearWater,
	float strollFlyChanceExplore,
	double strollFlyMinDist,
	double strollFlyMaxDist,
	boolean forageNearWater,
	SoundEvent ambient,
	SoundEvent scared,
	SoundEvent hurt,
	SoundEvent death,
	SoundEvent flap,
	String assetPrefix,
	List<ResourceKey<Biome>> spawnBiomes,
	int spawnWeight,
	int spawnMinGroup,
	int spawnMaxGroup
) {
	/** 栖息类型:决定行为集组合(湿地有水系行为;其余为通用觅食/闲逛) */
	public enum Habitat { WETLAND, FOREST, GRASSLAND, VILLAGE }

	/** geckolib 模型 / 动画资源键(<mod>:<前缀>) */
	public Identifier modelId() {
		return Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, assetPrefix);
	}

	/** 实体贴图资源键 */
	public Identifier textureId() {
		return Identifier.fromNamespaceAndPath(BirdWatchMod.MOD_ID, "textures/entity/" + assetPrefix + ".png");
	}

	/** 动画剪辑全名,如 idle → "animation.little_egret.idle"(动画 JSON 键须匹配) */
	public String animationKey(String clip) {
		return "animation." + assetPrefix + "." + clip;
	}
}
