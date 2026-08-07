#!/usr/bin/env python3
"""一次性脚本:为生物图鉴补全缺失的 60 种生物文案(zh/en)。
用法:python tools/add_bestiary_descs.py(幂等,已有 key 覆盖)
"""
import json
import pathlib

DESCS_ZH = {
    "allay": "轻盈的蓝色小精灵,会跟随给予它音符盒的玩家,帮忙拾取掉落物。",
    "armadillo": "身披鳞甲的温和生物,受惊时会蜷成球。掉落的鳞甲可以给狼制作护甲。",
    "bee": "勤劳的授粉者,在花朵间穿梭采蜜。蜂巢被破坏时它们会群起反击。",
    "blaze": "下界烈焰神殿的炽热守卫,浑身缠绕火焰。它们射出的火球极具威胁。",
    "breeze": "试炼密室中的风之元素,能凝聚风弹击退敌人。身体由风与云构成。",
    "camel": "沙漠中的高大家畜,可以双人骑乘。缓慢的步伐带着沙粒的节奏。",
    "camel_husk": "沙漠遗骸中苏醒的骆驼亡灵,身形高大而枯瘦。",
    "cave_spider": "洞穴中的毒蜘蛛,体型比普通蜘蛛更小,毒素却更加致命。",
    "dolphin": "海洋中的友善精灵,会与游泳的玩家嬉戏。它们引领迷路的旅人找到沉船宝藏。",
    "drowned": "溺亡的僵尸,游荡在海洋与河流深处。它们投掷三叉戟,偶尔也手持鹦鹉螺壳。",
    "elder_guardian": "海底神殿的古老统治者,体型庞大,目光能让周围的生物迟缓。",
    "ender_dragon": "末地的终末之龙,盘旋在紫颂树间。击败它是无数冒险者的目标。",
    "enderman": "末地的黑色高挑生物,瞬移与注视都会激怒它。直视它的眼睛是危险的。",
    "endermite": "末影人传送时留下的紫色小虫,短暂存续,容易被末影人攻击。",
    "evoker": "林地府邸的唤魔者,召唤尖牙与恼鬼。手持图腾的它精通黑暗法术。",
    "ghast": "下界的幽灵巨兽,发出婴儿般的哭声。它的火球能在远处轰击你。",
    "giant": "古老的巨型僵尸,只在代码与传说中存在。它不会被自然生成。",
    "glow_squid": "深海的发光鱿鱼,墨汁带着荧光。它的光在黑暗中格外醒目。",
    "guardian": "海底神殿的守卫,长着尖刺与独眼。它的激光会锁定入侵者。",
    "hoglin": "绯红森林的狂暴野猪,会攻击玩家。它们的肉是下界的重要食物。",
    "husk": "沙漠中干涸的僵尸,不会被阳光烧毁。被它击中会陷入饥饿。",
    "illusioner": "幻术师,能在战斗中制造分身与隐身。它们行踪诡秘,极少现身。",
    "iron_golem": "村庄的铁铸守护者,会保护村民与玩家。它的大手能轻易击飞敌人。",
    "llama": "高原的驮兽,会朝敌人吐唾沫。披上地毯后显得神气十足。",
    "magma_cube": "下界的熔岩史莱姆,蹦跳着分裂。它们的身体像烧红的石头。",
    "mooshroom": "蘑菇岛的神秘奶牛,身上长满红色蘑菇。用碗可以收集蘑菇汤。",
    "mule": "骡,由马与驴杂交而成,不能繁殖。耐力比马更胜一筹。",
    "nautilus": "远古的鹦鹉螺化石,在沉船与海洋中寻觅。它的壳是海洋的珍宝。",
    "ocelot": "丛林中的野生猫科动物,警惕而优雅。用鱼可以赢得它的信任。",
    "panda": "竹林中的黑白熊,慵懒地啃着竹子。有时会打滚,有时会打喷嚏。",
    "phantom": "失眠者的梦魇,在夜空盘旋。连续数夜不眠会让它们成群扑下。",
    "piglin": "下界的金色子民,痴迷于金锭。给它金锭会得到回礼,拿走金色物会激怒它。",
    "piglin_brute": "堡垒遗迹中的强力猪灵,手持金斧。它们不会因金锭而缓和敌意。",
    "pillager": "掠夺者,手持弩箭在村庄外游荡。它们戴着灰色兜帽,伺机而动。",
    "polar_bear": "冰原的霸主,白色皮毛下是强壮的躯体。保护幼崽时极具攻击性。",
    "pufferfish": "会鼓胀的河豚,满身尖刺。靠近它会被毒伤,刺中的瞬间也很危险。",
    "ravager": "林地府邸的巨兽,横冲直撞。它的冲撞能摧毁庄稼与阻挡物。",
    "shulker": "末地城的紫色甲壳生物,会发射追踪弹。子弹命中会让目标漂浮。",
    "silverfish": "要塞石砖间的银鱼,潜藏偷袭。被攻击时会呼唤同伴。",
    "skeleton_horse": "亡灵的马,在雷暴中诞生。骷髅骑手被击败后,它可能留下。",
    "slime": "沼泽与深层的绿色凝胶,分裂成更小的个体。它不会飞行,只会蹦跳。",
    "sniffer": "远古的嗅探兽,从蛋中孵化。它能嗅出埋藏的种子。",
    "snow_golem": "雪人,由雪块与南瓜头制成。它会朝敌人扔雪球。",
    "stray": "冰原的流浪骷髅,带着寒意。被它的箭击中会缓慢移动。",
    "strider": "熔岩上的行者,踩着炽热的岩浆行走。用鞍可以骑乘,菌柄引导方向。",
    "tadpole": "蝌蚪,蛙的幼体。在水中游动,慢慢长出后腿变成青蛙。",
    "trader_llama": "流浪商人的驼队,毛发装饰华丽。它们会吐唾沫驱赶狼。",
    "vex": "唤魔者召唤的恼鬼,小剑锋利,穿墙追击目标。",
    "villager": "村庄的居民,会交易各种物品。职业各不相同,铁匠、农民、图书管理员。",
    "vindicator": "掠夺者的斧手,手持铁斧的悍将。它们是林地府邸的守卫。",
    "wandering_trader": "行商,牵着两只羊驼四处游走。出售稀有的异域物品。",
    "warden": "深暗之城的远古守卫,没有眼睛,靠震动感知。它的声波能穿透墙壁。",
    "witch": "沼泽中的女巫,配制魔药。她会对敌人投掷药水,也会自饮治疗。",
    "wither": "凋灵,由灵魂沙与凋灵骷髅头组成。它的头颅会轰击一切,是毁灭的化身。",
    "wither_skeleton": "下界要塞的黑色骷髅,手持石剑。被击中会凋零衰败。",
    "zoglin": "被感染的猪灵,狂暴而疯狂。它会攻击任何生物,包括同类。",
    "zombie_horse": "僵尸马,在雷暴中与骷髅马一同出现。它们无法被驯服。",
    "zombie_nautilus": "溺尸与鹦鹉螺的奇异结合,在深海缓慢游荡。",
    "zombie_villager": "被感染的村民,目光呆滞。喷溅虚弱药水与金苹果可以治愈它。",
    "zombified_piglin": "下界的僵尸猪灵,成群游荡。攻击一只会引来一群报复。",
}

DESCS_EN = {
    "allay": "A light blue sprite that follows players who give it a noteblock, collecting dropped items.",
    "armadillo": "A gentle armored creature that curls into a ball when startled. Its scutes armor wolves.",
    "bee": "Hardworking pollinators flitting between flowers. Disturb a hive and they swarm.",
    "blaze": "Blazing guardians of nether fortresses, wreathed in flame. Their fireballs are deadly.",
    "breeze": "Wind elementals of trial chambers that hurl gusts. Their bodies are made of air and cloud.",
    "camel": "Tall desert livestock, rideable by two. Their slow gait carries desert rhythm.",
    "camel_husk": "Undead camels awakened in desert ruins, tall and gaunt.",
    "cave_spider": "Venomous spiders of caves, smaller but far more poisonous than common ones.",
    "dolphin": "Friendly spirits of the sea that play with swimmers. They lead travelers to shipwreck treasure.",
    "drowned": "Drowned zombies haunting oceans and rivers. They throw tridents and sometimes carry nautilus shells.",
    "elder_guardian": "Ancient rulers of ocean monuments, huge and slow-moving with a gaze that weakens.",
    "ender_dragon": "The dragon of the End, circling the chorus trees. Defeating it is many adventurers' goal.",
    "enderman": "Tall black beings of the End. Teleporting and being stared at enrages them. Eye contact is dangerous.",
    "endermite": "Tiny purple pests left by teleporting endermen. Short-lived and hated by their creators.",
    "evoker": "Evokers of woodland mansions summon fangs and vexes. They wield dark magic.",
    "ghast": "Ghostly giants of the Nether with a baby's cry. Their fireballs blast from afar.",
    "giant": "Ancient giant zombies that exist only in code and legend. They never spawn naturally.",
    "glow_squid": "Luminous squid of the deep. Their ink glows, bright in the dark.",
    "guardian": "Guards of ocean monuments with spines and a single eye. Their laser locks onto intruders.",
    "hoglin": "Savage boars of the crimson forest that attack players. Their meat feeds the Nether.",
    "husk": "Dried zombies of the desert, immune to sunlight. Their hits bring hunger.",
    "illusioner": "Illusionists that create decoys and turn invisible. Mysterious and rarely seen.",
    "iron_golem": "Iron guardians of villages that protect villagers and players. Their fists send foes flying.",
    "llama": "Highland pack animals that spit at threats. Dressed in carpets, they look proud.",
    "magma_cube": "Molten slimes of the Nether that split as they bounce. Their bodies glow like hot stone.",
    "mooshroom": "Mysterious mushroom cows of mushroom islands. A bowl collects their stew.",
    "mule": "Cross of horse and donkey, unable to breed. Sturdier than either parent.",
    "nautilus": "Ancient nautilus fossils found in shipwrecks and the deep. Their shells are ocean treasure.",
    "ocelot": "Wild cats of the jungle, wary and elegant. Fish earn their trust.",
    "panda": "Black-and-white bears of bamboo groves, lazily munching. Sometimes they roll, sometimes sneeze.",
    "phantom": "Nightmares of the sleepless, circling the night sky. Nights without rest bring flocks.",
    "piglin": "Golden folk of the Nether obsessed with gold ingots. Offerings earn gifts; theft enrages them.",
    "piglin_brute": "Powerful piglins of bastions wielding golden axes. Gold cannot soothe their hostility.",
    "pillager": "Crossbow raiders roaming near villages in gray hoods, waiting for their moment.",
    "polar_bear": "Lords of the ice, powerful under white fur. Fierce when protecting cubs.",
    "pufferfish": "Puffing fish covered in spines. Getting close poisons you.",
    "ravager": "Beasts of woodland mansions that charge through. Their rampage flattens crops and barricades.",
    "shulker": "Purple shelled creatures of end cities that fire homing projectiles. Hits make you float.",
    "silverfish": "Silverfish lurking in stronghold stone, ambushing. Harmed, they call their kin.",
    "skeleton_horse": "Undead horses born in thunderstorms. They may remain when their riders fall.",
    "slime": "Green gel of swamps and deep caves that splits into smaller selves. It hops, never flies.",
    "sniffer": "Ancient sniffers hatched from eggs. They can sniff out buried seeds.",
    "snow_golem": "Snowmen of snow blocks and pumpkin heads. They pelt foes with snowballs.",
    "stray": "Frostbitten skeletons of the ice wastes. Their arrows slow you down.",
    "strider": "Walkers on lava, treading molten ground. Saddle them and steer with fungus on a stick.",
    "tadpole": "Tadpoles, the young of frogs. They swim and slowly grow legs to become frogs.",
    "trader_llama": "Pack llamas of wandering traders, decked in colorful cloth. They spit at wolves.",
    "vex": "Vexes summoned by evokers, small blades sharp, phasing through walls to chase.",
    "villager": "Villagers who trade goods of every kind. Blacksmiths, farmers, librarians and more.",
    "vindicator": "Axe-wielding brutes of raiders, guards of woodland mansions.",
    "wandering_trader": "Traders who walk with two llamas, selling rare exotic goods.",
    "warden": "Ancient guardians of the deep dark, eyeless, sensing vibration. Their sonic boom pierces walls.",
    "witch": "Witches of the swamps brewing potions. They throw them at foes and drink to heal.",
    "wither": "The Wither, born of soul sand and wither skulls. Its heads blast all; it is destruction incarnate.",
    "wither_skeleton": "Black skeletons of nether fortresses with stone swords. Their strikes wither you.",
    "zoglin": "Infected piglins, frenzied and mad. They attack anything, even their own kind.",
    "zombie_horse": "Zombie horses appearing in thunderstorms with skeleton horses. They cannot be tamed.",
    "zombie_nautilus": "A strange union of drowned and nautilus, drifting slowly in the deep.",
    "zombie_villager": "Infected villagers with hollow eyes. A splash of weakness and a golden apple cure them.",
    "zombified_piglin": "Zombified piglins of the Nether, wandering in groups. Strike one, face the mob.",
}


def load(p):
    return json.loads(pathlib.Path(p).read_text(encoding="utf-8"))


def save(p, d):
    pathlib.Path(p).write_text(json.dumps(d, ensure_ascii=False, indent="\t"), encoding="utf-8")


def main():
    zh = load("src/main/resources/assets/birdwatch/lang/zh_cn.json")
    en = load("src/main/resources/assets/birdwatch/lang/en_us.json")
    for k, v in DESCS_ZH.items():
        zh[f"bestiary.birdwatch.{k}.desc"] = v
    for k, v in DESCS_EN.items():
        en[f"bestiary.birdwatch.{k}.desc"] = v
    save("src/main/resources/assets/birdwatch/lang/zh_cn.json", zh)
    save("src/main/resources/assets/birdwatch/lang/en_us.json", en)
    print(f"补全 {len(DESCS_ZH)} 种(zh) + {len(DESCS_EN)} 种(en)")


if __name__ == "__main__":
    main()
