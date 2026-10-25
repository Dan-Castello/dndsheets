package net.hawthorn.dndsheets.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * <p>The mod's own creature: ONE entity type that wears any of the archetype models (dragon, goblinoid, ...).
 * Which one is decided by two synced strings, {@code model} (the archetype: where the geometry, animations and
 * texture folder live) and {@code variant} (which texture of that archetype: a dragon's colour). Adding an
 * archetype is therefore three resource files and one line in {@code monsters.json}, with no Java.</p>
 *
 * <p>A monster picks it with {@code appearance.model} / {@code appearance.variant}; its {@code baseEntity} stays a
 * vanilla entity, the fallback used when the {@code ownCreatureModels} config is off or a skin pack for another
 * creature mod ({@link net.hawthorn.dndsheets.MonsterSkins}) applies.</p>
 *
 * <p>Animation names are the plain keys {@code idle, fly, attack, hurt, death} in every archetype's file, so the
 * Java never names an archetype. A missing animation is simply not played.</p>
 */
public class MonsterModelEntity extends PathfinderMob implements GeoEntity {
	public static final String DEFAULT_MODEL = "dragon";
	public static final String DEFAULT_VARIANT = "red";

	private static final EntityDataAccessor<String> MODEL = SynchedEntityData.defineId(MonsterModelEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> VARIANT = SynchedEntityData.defineId(MonsterModelEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> BABY = SynchedEntityData.defineId(MonsterModelEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> FLYING = SynchedEntityData.defineId(MonsterModelEntity.class, EntityDataSerializers.BOOLEAN);

	private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

	public MonsterModelEntity(EntityType<? extends MonsterModelEntity> type, Level level) {
		super(type, level);
		setPersistenceRequired(); //A placed monster is never "far away"; it despawns only when the DM removes it.
	}

	public static AttributeSupplier.Builder createAttributes() {
		//Real HP/AC live in the stat block and the dndsheets NBT tag, like for every monster; this is only so the entity is valid.
		return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.25);
	}

	@Override
	protected void defineSynchedData() {
		super.defineSynchedData();
		entityData.define(MODEL, DEFAULT_MODEL);
		entityData.define(VARIANT, DEFAULT_VARIANT);
		entityData.define(FLYING, false);
		entityData.define(BABY, false);
	}

	//"appearance.baby" (a dragon wyrmling): half size, hitbox included. Vanilla spells it isBaby()/setBaby() on Mob.
	@Override
	public boolean isBaby() {
		return entityData.get(BABY);
	}

	@Override
	public void setBaby(boolean baby) {
		entityData.set(BABY, baby);
		refreshDimensions();
	}

	@Override
	public EntityDimensions getDimensions(Pose pose) {
		return isBaby() ? super.getDimensions(pose).scale(0.5f) : super.getDimensions(pose);
	}

	public String getModel() {
		String model = entityData.get(MODEL);
		return model.isEmpty() ? DEFAULT_MODEL : model;
	}

	public String getVariant() {
		String variant = entityData.get(VARIANT);
		return variant.isEmpty() ? DEFAULT_VARIANT : variant;
	}

	public boolean isFlyingPose() {
		return entityData.get(FLYING);
	}

	/** Called by {@code MonsterRegistry.applyLooks}; null/blank keeps the default. */
	public void configure(String model, String variant, boolean flying) {
		if (model != null && !model.isBlank()) entityData.set(MODEL, model);
		if (variant != null && !variant.isBlank()) entityData.set(VARIANT, variant);
		entityData.set(FLYING, flying);
	}

	/** Plays a one-shot ("attack", "hurt") on whatever archetype this is; no-op on any other entity. */
	public static void play(Entity entity, String trigger) {
		if (entity instanceof MonsterModelEntity self && !self.level().isClientSide) self.triggerAnim("action", trigger);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putString("Model", entityData.get(MODEL));
		tag.putString("Variant", entityData.get(VARIANT));
		tag.putBoolean("FlyingPose", isFlyingPose());
		tag.putBoolean("Baby", isBaby());
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		configure(tag.getString("Model"), tag.getString("Variant"), tag.getBoolean("FlyingPose"));
		setBaby(tag.getBoolean("Baby"));
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		boolean hurt = super.hurt(source, amount);
		if (hurt && isAlive()) play(this, "hurt");
		return hurt;
	}

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
		controllers.add(new AnimationController<>(this, "main", 5, this::mainAnimation));
		controllers.add(new AnimationController<>(this, "action", 0, state -> PlayState.STOP)
			.triggerableAnim("attack", RawAnimation.begin().thenPlay("attack"))
			.triggerableAnim("hurt", RawAnimation.begin().thenPlay("hurt")));
	}

	private PlayState mainAnimation(AnimationState<MonsterModelEntity> state) {
		if (isDeadOrDying()) return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("death"));
		return state.setAndContinue(RawAnimation.begin().thenLoop(isFlyingPose() ? "fly" : "idle"));
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return cache;
	}
}
