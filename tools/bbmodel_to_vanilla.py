#!/usr/bin/env python3
"""bbmodel(java_block)→ vanilla 实体模型转换(M2b 白鹭)。

Blockbench 的 java_block 工程没有骨骼(10 个 cube、自由 UV、旋转原点在各 cube 上)。
本脚本:
1. 把每个 cube 的旋转原点变成 ModelPart 枢轴,生成 createBodyLayer() Java 代码
   (枢轴覆盖表校正关节:头/喙共枢轴、翅移到肩部)
2. 把自由 UV 重排成 vanilla 实体盒十字布局,像素级复制(用户画作保留),
   输出新贴图(优先 32×32,放不下自动升级 64×32)

用法:python tools/bbmodel_to_vanilla.py
产物:
- blockbench/little_egret/body_layer.java —— createBodyLayer() 方法体,集成进 HeronModel
- src/main/resources/assets/birdwatch/textures/entity/heron.png —— 重排后的贴图

用户工作流:在 Blockbench 改模型/画贴图 → 重跑本脚本 → 游戏内验证。
注意:up/down 面旋转与 west 镜像若在游戏内发现方向不对,改 FACE_ORIENT 常量后重跑。
"""

import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
BBMODEL = ROOT / "blockbench" / "little_egret" / "little_egret.bbmodel"
TEX_IN = ROOT / "blockbench" / "little_egret" / "textures" / "heron.png"
TEX_OUT = ROOT / "src" / "main" / "resources" / "assets" / "birdwatch" / "textures" / "entity" / "heron.png"
JAVA_OUT = ROOT / "blockbench" / "little_egret" / "body_layer.java"

# 枢轴覆盖(vanilla 空间 [x, y, z]):头/喙共用头顶枢轴;翅枢轴移到肩部
PIVOT_OVERRIDES = {
    "head": (-1.0, 6.0, -5.0),
    "beak": (-1.0, 6.0, -5.0),
    "wingLeft": (-2.0, 16.0, 1.0),
    "wingRight": (2.0, 16.0, 1.0),
}

# 面取向(复制时变换):vanilla 盒布局 vs Blockbench 自由 UV
# "flip_x" = 水平镜像;"rot90_cw" = 顺时针 90°;"rot90_ccw" = 逆时针 90°;"none" = 原样
FACE_ORIENT = {
    "north": "none",
    "south": "none",
    "east": "none",
    "west": "flip_x",
    "up": "rot90_cw",
    "down": "rot90_ccw",
}


def parse_bbmodel():
    bb = json.loads(BBMODEL.read_text(encoding="utf-8"))
    res_w, res_h = bb["resolution"]["width"], bb["resolution"]["height"]
    cubes = {}
    order = []
    by_uuid = {e["uuid"]: e for e in bb["elements"] if "uuid" in e}

    def walk(node):
        if isinstance(node, str):
            node = by_uuid.get(node)
            if node is None:
                return
        if node.get("type") == "cube":
            name = node["name"]
            cubes[name] = node
            order.append(name)
        for c in node.get("children", []):
            walk(c)

    for root in bb["outliner"]:
        walk(root)
    return res_w, res_h, cubes, order


def vanilla_pivot(bb_origin, name):
    if name in PIVOT_OVERRIDES:
        return PIVOT_OVERRIDES[name]
    ox, oy, oz = bb_origin
    return (ox - 8.0, 24.0 - oy, oz - 8.0)


def face_rect(cube, face):
    """用户贴图上的面矩形 [u1, v1, u2, v2](可倒序,归一化)"""
    uv = cube["faces"][face]["uv"]
    u1, v1, u2, v2 = uv
    if u2 < u1:
        u1, u2 = u2, u1
    if v2 < v1:
        v1, v2 = v2, v1
    return u1, v1, u2, v2


def copy_face(src, dst, srect, drect, orient):
    """从源贴图把面像素复制到目标位置,按 orient 变换"""
    u1, v1, u2, v2 = srect
    a = src[v1:v2, u1:u2]
    if orient == "flip_x":
        a = a[:, ::-1]
    elif orient == "rot90_cw":
        a = np.rot90(a, k=-1)
    elif orient == "rot90_ccw":
        a = np.rot90(a, k=1)
    x1, y1, x2, y2 = drect
    dst[y1:y2, x1:x2] = a


def main():
    res_w, res_h, cubes, order = parse_bbmodel()
    tex = np.array(Image.open(TEX_IN).convert("RGBA"))
    assert tex.shape[:2] == (res_h, res_w), f"贴图 {tex.shape[:2]} 与工程 {res_h}x{res_w} 不符"

    # ---- 计算每个 cube 的 vanilla 参数 + 十字布局块尺寸 ----
    parts = []
    for name in order:
        c = cubes[name]
        f, t, o = c["from"], c["to"], c["origin"]
        w, h, d = t[0] - f[0], t[1] - f[1], t[2] - f[2]
        px, py, pz = vanilla_pivot(o, name)
        box = (f[0] - 8.0 - px, 24.0 - f[1] - py, f[2] - 8.0 - pz, w, h, d)
        parts.append({"name": name, "pivot": (px, py, pz), "box": box, "w": w, "h": h, "d": d})

    # ---- 书架打包:块宽 2w+2d,块高 h+d ----
    def block_size(p):
        return 2 * p["w"] + 2 * p["d"], p["h"] + p["d"]

    # 从大到小排序,简单贪心行打包
    order_by_size = sorted(parts, key=lambda p: -block_size(p)[0])
    placements = {}  # name -> (bx, by) 块左上角
    W = 32
    rows = []  # [(y, height, [(name, x)])]
    cur_y, cur_h, cur_row = 0, 0, []
    for p in order_by_size:
        bw, bh = block_size(p)
        if cur_row and cur_y + bh > 32:
            # 新行(限高 32)
            if cur_y + bh > 32:
                # 单块就放不下 32 高 → 升级 64 宽
                W = 64
            cur_y += cur_h
            cur_h = 0
            cur_row = []
        # 行内放不下的换行
        row_used = sum(block_size(p2)[0] for p2, _ in cur_row)
        if cur_row and row_used + bw > W:
            cur_y += cur_h
            cur_h = 0
            cur_row = []
        placements[p["name"]] = (row_used if cur_row else 0, cur_y)
        cur_row.append((p, row_used))
        cur_h = max(cur_h, bh)
        rows_keep = True
    total_h = cur_y + cur_h
    H = max(total_h, 32)

    # ---- 生成新贴图 ----
    dst = np.zeros((H, W, 4), dtype=np.uint8)
    texoffs = {}
    for p in parts:
        bx, by = placements[p["name"]]
        w, h, d = p["w"], p["h"], p["d"]
        # vanilla 实体盒布局(texOffs = (bx, by)):
        #   up: (bx+d, by, w, d)   down: (bx+d+w, by, w, d)
        #   west: (bx, by+h, d, h) north: (bx+d, by+h, w, h)
        #   east: (bx+d+w, by+h, d, h) south: (bx+d+w+d, by+h, w, h)
        faces = {
            "up": (bx + d, by, bx + d + w, by + d),
            "down": (bx + d + w, by, bx + d + w + w, by + d),
            "west": (bx, by + h, bx + d, by + h + h),
            "north": (bx + d, by + h, bx + d + w, by + h + h),
            "east": (bx + d + w, by + h, bx + d + w + d, by + h + h),
            "south": (bx + d + w + d, by + h, bx + d + w + d + w, by + h + h),
        }
        for face, drect in faces.items():
            copy_face(tex, dst, face_rect(cubes[p["name"]], face), drect, FACE_ORIENT[face])
        texoffs[p["name"]] = (bx, by)

    out = Image.fromarray(dst, "RGBA")
    out.save(TEX_OUT)
    print(f"贴图: {W}x{H} → {TEX_OUT}")

    # ---- 生成 Java ----
    lines = []
    for p in parts:
        bx, by = texoffs[p["name"]]
        x, y, z, w, h, d = p["box"]
        px, py, pz = p["pivot"]
        lines.append(
            f'\t\tpart.addOrReplaceChild("{p["name"]}", CubeListBuilder.create().texOffs({bx}, {by})\n'
            f"\t\t\t.addBox({x:g}F, {y:g}F, {z:g}F, {w:g}F, {h:g}F, {d:g}F),\n"
            f"\t\t\tPartPose.offset({px:g}F, {py:g}F, {pz:g}F));"
        )
    java = f"\t\t// 由 tools/bbmodel_to_vanilla.py 从 blockbench/little_egret 生成,请勿手改\n" + "\n".join(lines) + "\n"
    JAVA_OUT.write_text(java, encoding="utf-8")
    print(f"Java: {JAVA_OUT}")


if __name__ == "__main__":
    main()
