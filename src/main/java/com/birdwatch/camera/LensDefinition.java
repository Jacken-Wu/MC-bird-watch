package com.birdwatch.camera;

/**
 * 镜头定义。属性轴:焦距范围(变焦可调/定焦固定)、光圈范围、是否支持自动对焦(AF 速度,M2 使用)。
 */
public record LensDefinition(
	String id,
	String nameKey,
	LensType type,
	int minFocal,
	int maxFocal,
	float minAperture,
	float maxAperture,
	boolean afSupported,
	int afSpeedTicks
) {
	public enum LensType {
		PRIME, ZOOM
	}
}
