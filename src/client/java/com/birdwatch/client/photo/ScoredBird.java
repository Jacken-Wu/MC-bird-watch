package com.birdwatch.client.photo;

/**
 * 画面内单只鸟的评分结果(M2a 六维评分)。
 *
 * 档位:tier ≥60 解锁 / ≥80 优秀 / ≥95 完美(DESIGN 定稿)。
 * 稳定性并入运动模糊计算,但保留独立分供调试/图鉴参考。
 */
public record ScoredBird(
	String speciesId,
	int score,
	String tier,
	double focus,
	double composition,
	double motion,
	double noise,
	double occlusion,
	double stability
) {
	/** 档位判定 */
	public static String tierOf(int score) {
		if (score >= 95) {
			return "perfect";
		}
		if (score >= 80) {
			return "excellent";
		}
		if (score >= 60) {
			return "unlock";
		}
		return "none";
	}

	public boolean qualifies() {
		return score >= 60;
	}
}
