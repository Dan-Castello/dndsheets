package net.hawthorn.dndsheets.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.entity.MonsterModelEntity;
import net.hawthorn.dndsheets.init.DndsheetsModEntities;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Draws {@link MonsterModelEntity}: one renderer for every archetype, the files chosen from the entity's synced
 * {@code model} / {@code variant}. Layout (all under {@code assets/dndsheets/}):
 * {@code geo/entity/<model>.geo.json}, {@code animations/entity/<model>.animation.json},
 * {@code textures/entity/<model>/mask.png} + {@code variants.json} (coloured on the client by {@link MonsterTextures}).</p>
 */
@Mod.EventBusSubscriber(modid = DndsheetsMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class MonsterModelRenderer extends GeoEntityRenderer<MonsterModelEntity> {
	public MonsterModelRenderer(EntityRendererProvider.Context context) {
		super(context, new Model());
		this.shadowRadius = 0.6f;
		//appearance.mainHand/offHand: drawn on the "item_r"/"item_l" bones of the bipeds (a goblin's scimitar).
		addRenderLayer(new BlockAndItemGeoLayer<>(this) {
			@Override
			protected ItemStack getStackForBone(GeoBone bone, MonsterModelEntity entity) {
				return switch (bone.getName()) {
					case "item_r" -> entity.getMainHandItem();
					case "item_l" -> entity.getOffhandItem();
					default -> null;
				};
			}

			@Override
			protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, MonsterModelEntity entity) {
				return bone.getName().equals("item_l") ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
			}

			@Override
			protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, MonsterModelEntity entity,
					MultiBufferSource buffers, float partialTick, int packedLight, int packedOverlay) {
				//Vanilla's ItemInHandLayer, replayed. The items' third-person display transforms (a shield's above all) are
				//authored for vanilla's model space, which is GeckoLib's turned 180 degrees about Z (vanilla draws with
				//scale(-1, -1, 1)): go back to it first, or a shield in the left hand ends up above the head and a club
				//hangs backwards. Then vanilla's own steps; its final -0.625 walks from the shoulder to the hand, and our
				//item bone already IS the hand, so it is left out.
				boolean left = bone.getName().equals("item_l");
				poseStack.mulPose(Axis.ZP.rotationDegrees(180));
				poseStack.mulPose(Axis.XP.rotationDegrees(-90));
				poseStack.mulPose(Axis.YP.rotationDegrees(180));
				poseStack.translate((left ? -1 : 1) / 16f, 0.125f, 0);
				//Not super: GeckoLib's call has no left-hand flag, and vanilla mirrors an item's third-person transform
				//for the left hand (a shield's is 12 px off for that hand against -4 for the right: drawn unmirrored it
				//floated a block up, over the head). This is ItemInHandRenderer's own call.
				Minecraft.getInstance().getItemRenderer().renderStatic(entity, stack, getTransformTypeForStack(bone, stack, entity),
					left, poseStack, buffers, entity.level(), packedLight, packedOverlay, entity.getId());
			}
		});
	}

	@Override
	protected float getDeathMaxRotation(MonsterModelEntity entity) {
		return 0; //The archetype's own "death" animation plays instead of the vanilla fall-over.
	}

	@Override
	public void scaleModelForRender(float widthScale, float heightScale, PoseStack poseStack, MonsterModelEntity entity,
			BakedGeoModel model, boolean isReRender, float partialTick, int packedLight, int packedOverlay) {
		float scale = (entity.isBaby() ? 0.5f : 1f) * modelScale(entity.getModel());
		super.scaleModelForRender(widthScale * scale, heightScale * scale, poseStack, entity, model, isReRender, partialTick, packedLight, packedOverlay);
	}

	//creatures/scales.json: {model: factor} for imported models authored at another size than our Medium base (a
	//6-block drake is a Large wyvern at 0.4). Read once, dropped on every resource reload; a model not listed is 1.
	private static Map<String, Float> scales;

	private static float modelScale(String model) {
		if (scales == null) {
			Map<String, Float> read = new ConcurrentHashMap<>();
			try (var in = net.minecraft.client.Minecraft.getInstance().getResourceManager()
					.open(new ResourceLocation(DndsheetsMod.MODID, "creatures/scales.json"))) {
				com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
					.getAsJsonObject().entrySet().forEach(e -> read.put(e.getKey(), e.getValue().getAsFloat()));
			} catch (Exception e) {
				//No file (no imported model) or a broken one: every model at its own size.
			}
			scales = read;
		}
		return scales.getOrDefault(model, 1f);
	}

	//Palettes and masks may change with a resource pack: recolour on the next draw after every reload.
	@SubscribeEvent
	public static void reloadTextures(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
		event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> {
			MonsterTextures.clear();
			scales = null;
		});
	}

	@SubscribeEvent
	public static void register(EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(DndsheetsModEntities.MONSTER_MODEL.get(), MonsterModelRenderer::new);
	}

	private static class Model extends GeoModel<MonsterModelEntity> {
		//Building a ResourceLocation per frame would allocate for nothing; a handful of distinct keys, so a plain map is enough.
		private final Map<String, ResourceLocation> cache = new ConcurrentHashMap<>();

		private ResourceLocation at(String path) {
			return cache.computeIfAbsent(path, p -> new ResourceLocation(DndsheetsMod.MODID, p));
		}

		//A model a resource pack removed (or a newer server's archetype) would crash GeckoLib: draw the default one instead.
		private String model(MonsterModelEntity entity) {
			String model = entity.getModel();
			if (GeckoLibCache.getBakedModels().containsKey(at("geo/entity/" + model + ".geo.json"))) return model;
			if (cache.putIfAbsent("missing:" + model, at("missing")) == null)
				DndsheetsMod.LOGGER.warn("dndsheets: creature model \"{}\" is not loaded; drawing \"{}\" instead.", model, MonsterModelEntity.DEFAULT_MODEL);
			return MonsterModelEntity.DEFAULT_MODEL;
		}

		@Override
		public ResourceLocation getModelResource(MonsterModelEntity entity) {
			return at("geo/entity/" + model(entity) + ".geo.json");
		}

		@Override
		public ResourceLocation getTextureResource(MonsterModelEntity entity) {
			String model = model(entity);
			return MonsterTextures.get(model, model.equals(entity.getModel()) ? entity.getVariant() : MonsterModelEntity.DEFAULT_VARIANT);
		}

		@Override
		public ResourceLocation getAnimationResource(MonsterModelEntity entity) {
			return at("animations/entity/" + model(entity) + ".animation.json");
		}
	}
}
