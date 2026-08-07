package com.birdwatch.client.handbook;

import com.birdwatch.network.ModNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 生物图鉴进度(客户端视图):已解锁生物 id 集合。
 * 权威数据在服务端(玩家附件);客户端打开图鉴时 C2S 查询,
 * S2C BestiaryStatePayload 回传后更新本视图。
 */
public final class BestiaryProgress {
	private static final Set<String> UNLOCKED = new HashSet<>();

	private BestiaryProgress() {
	}

	/** 打开图鉴时请求最新状态(服务端附件为权威) */
	public static void request() {
		ClientPlayNetworking.send(new ModNetworking.BestiaryQueryPayload());
	}

	/** S2C 状态回传更新 */
	public static void apply(List<String> unlocked) {
		UNLOCKED.clear();
		UNLOCKED.addAll(unlocked);
	}

	public static boolean isUnlocked(String entityId) {
		return UNLOCKED.contains(entityId);
	}

	public static Set<String> unlocked() {
		return Set.copyOf(UNLOCKED);
	}
}
