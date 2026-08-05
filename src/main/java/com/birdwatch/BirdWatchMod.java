package com.birdwatch;

import com.birdwatch.config.BirdWatchConfig;
import com.birdwatch.network.ModNetworking;
import com.birdwatch.registry.ModItems;
import net.fabricmc.api.ModInitializer;
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
		LOGGER.info("[BirdWatch] 观鸟模组初始化完成 (debug={})", BirdWatchConfig.debug);
	}
}
