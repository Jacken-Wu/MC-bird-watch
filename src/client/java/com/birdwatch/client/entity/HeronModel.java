package com.birdwatch.client.entity;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * 白鹭静态模型(M2a 占位,代码拼几何体)。
 *
 * 结构:身体 + 2 节长颈 + 头 + 长喙 + 2 腿 + 2 翅 + 尾。
 * M2b 由用户精修贴图/模型并接入动画,此文件届时替换。
 */
public class HeronModel extends EntityModel<EntityRenderState> {
	private final ModelPart root;

	public HeronModel(ModelPart root) {
		super(root);
		this.root = root;
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition part = mesh.getRoot();

		// 身体(躯干,略微前倾的船形)
		part.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-3.0F, -3.0F, -4.0F, 6.0F, 5.0F, 9.0F),
			PartPose.offset(0.0F, 14.0F, 1.0F));

		// 脖子:两节(下颈 + 上颈),长而前弯
		part.addOrReplaceChild("neckLower", CubeListBuilder.create().texOffs(0, 14)
			.addBox(-1.0F, -6.0F, -1.0F, 2.0F, 6.0F, 2.0F),
			PartPose.offset(0.0F, 11.0F, -2.0F));
		part.addOrReplaceChild("neckUpper", CubeListBuilder.create().texOffs(8, 14)
			.addBox(-1.0F, -6.0F, -1.0F, 2.0F, 6.0F, 2.0F),
			PartPose.offset(0.0F, 5.5F, -3.0F));

		// 头 + 长喙(黄色)
		part.addOrReplaceChild("head", CubeListBuilder.create().texOffs(16, 0)
			.addBox(-1.5F, -2.0F, -2.0F, 3.0F, 3.0F, 3.0F),
			PartPose.offset(0.0F, 0.0F, -4.0F));
		part.addOrReplaceChild("beak", CubeListBuilder.create().texOffs(24, 0)
			.addBox(-0.5F, -1.0F, -5.0F, 1.0F, 1.0F, 5.0F),
			PartPose.offset(0.0F, 0.0F, -4.0F));

		// 腿(细长,涉禽特征)
		part.addOrReplaceChild("legLeft", CubeListBuilder.create().texOffs(0, 22)
			.addBox(-0.5F, 0.0F, -0.5F, 1.0F, 5.0F, 1.0F),
			PartPose.offset(-1.5F, 19.0F, 2.0F));
		part.addOrReplaceChild("legRight", CubeListBuilder.create().texOffs(4, 22)
			.addBox(-0.5F, 0.0F, -0.5F, 1.0F, 5.0F, 1.0F),
			PartPose.offset(1.5F, 19.0F, 2.0F));

		// 翅膀(收拢贴在身体两侧,M2b 展开/扇翅动画)
		part.addOrReplaceChild("wingLeft", CubeListBuilder.create().texOffs(30, 0)
			.addBox(-2.0F, 0.0F, -3.5F, 2.0F, 1.0F, 8.0F),
			PartPose.offset(-3.0F, 12.5F, 1.0F));
		part.addOrReplaceChild("wingRight", CubeListBuilder.create().texOffs(42, 0)
			.addBox(0.0F, 0.0F, -3.5F, 2.0F, 1.0F, 8.0F),
			PartPose.offset(3.0F, 12.5F, 1.0F));

		// 尾羽(上翘)
		part.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(28, 14)
			.addBox(-2.0F, 0.0F, 0.0F, 4.0F, 1.0F, 4.0F),
			PartPose.offset(0.0F, 12.0F, 6.0F));

		return LayerDefinition.create(mesh, 64, 32);
	}

	@Override
	public void setupAnim(EntityRenderState state) {
		// 静态模型:无动画(M2b 接入)
	}

	public ModelPart getRoot() {
		return root;
	}
}
