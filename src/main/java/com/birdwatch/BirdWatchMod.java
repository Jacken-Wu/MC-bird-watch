package com.birdwatch;

import com.birdwatch.advancement.HandbookUnlockTrigger;
import com.birdwatch.advancement.PhotoRatedTrigger;
import com.birdwatch.config.BirdWatchConfig;
import com.birdwatch.event.HandbookHandler;
import com.birdwatch.network.ModNetworking;
import com.birdwatch.registry.ModEntities;
import com.birdwatch.registry.ModItems;
import com.birdwatch.registry.ModSounds;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BirdWatchMod implements ModInitializer {
	public static final String MOD_ID = "birdwatch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		BirdWatchConfig.load();
		ModItems.registerAll();
		ModNetworking.register();
		com.birdwatch.menu.ModMenuTypes.register();
		ModSounds.registerAll();
		ModEntities.registerAll();
		HandbookHandler.register();
		registerAdvancementTriggers();
		LOGGER.info("[BirdWatch] 观鸟模组初始化完成 (debug={})", BirdWatchConfig.debug);
	}

	/** 自定义成就触发器注册(成就 JSON 的 "trigger" 由此解析) */
	private static void registerAdvancementTriggers() {
		Registry.register(net.minecraft.core.registries.BuiltInRegistries.TRIGGER_TYPES,
			Identifier.fromNamespaceAndPath(MOD_ID, "photo_rated"), PhotoRatedTrigger.INSTANCE);
		Registry.register(net.minecraft.core.registries.BuiltInRegistries.TRIGGER_TYPES,
			Identifier.fromNamespaceAndPath(MOD_ID, "handbook_unlock"), HandbookUnlockTrigger.INSTANCE);
		Registry.register(net.minecraft.core.registries.BuiltInRegistries.TRIGGER_TYPES,
			Identifier.fromNamespaceAndPath(MOD_ID, "photo_taken"), com.birdwatch.advancement.PhotoTakenTrigger.INSTANCE);
		Registry.register(net.minecraft.core.registries.BuiltInRegistries.TRIGGER_TYPES,
			Identifier.fromNamespaceAndPath(MOD_ID, "bestiary_full"), com.birdwatch.advancement.BestiaryFullTrigger.INSTANCE);
	}
}
