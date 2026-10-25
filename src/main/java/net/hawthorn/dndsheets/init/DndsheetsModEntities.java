package net.hawthorn.dndsheets.init;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.entity.MonsterModelEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The mod's only entity type: {@link MonsterModelEntity}, which wears whichever archetype model the monster asks for. */
public class DndsheetsModEntities {
	public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, DndsheetsMod.MODID);

	//MISC: never spawns naturally; it only exists because MonsterRegistry.spawnAt created it. 1x1 hitbox, scaled per size by Pehkui.
	public static final RegistryObject<EntityType<MonsterModelEntity>> MONSTER_MODEL = REGISTRY.register("monster_model",
		() -> EntityType.Builder.of(MonsterModelEntity::new, MobCategory.MISC).sized(1.0f, 1.0f).clientTrackingRange(10).build("monster_model"));

	public static void registerAttributes(EntityAttributeCreationEvent event) {
		event.put(MONSTER_MODEL.get(), MonsterModelEntity.createAttributes().build());
	}
}
