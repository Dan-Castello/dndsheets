package net.hawthorn.dndsheets.api;

import java.util.List;

/**
 * <p>Datos para registrar un arma vía {@link DndSheetsApi#registerWeapon}. Reemplaza el antiguo overload
 * de 10 parámetros {@code String} posicionales — varios consecutivos del mismo tipo ({@code damageType},
 * {@code hands}) eran intercambiables sin que el compilador lo detectara. Aquí los campos opcionales se
 * fijan por nombre encadenando métodos, en vez de por posición.</p>
 *
 * <p>Ejemplo: {@code new WeaponRegistration("dndsheets:mysword", "1d8", "str", "Mi Espada",
 * "minecraft:iron_sword").damageType("cortante").hands("two")}</p>
 */
public final class WeaponRegistration {
	private final String id, dice, ability, displayName, baseItemId;
	private String damageType = "fisico";
	private String hands = "one";
	private String versatileDice = null;
	private List<String> classes = List.of();
	private Integer customModelData = null;

	public WeaponRegistration(String id, String dice, String ability, String displayName, String baseItemId) {
		this.id = id;
		this.dice = dice;
		this.ability = ability;
		this.displayName = displayName;
		this.baseItemId = baseItemId;
	}

	/** Tipo de daño 5e en minúsculas (p.ej. "cortante", "perforante"); por defecto "fisico". */
	public WeaponRegistration damageType(String damageType) {
		this.damageType = damageType;
		return this;
	}

	/** "one" (defecto), "two", o "versatile" (ver {@link #versatileDice}). */
	public WeaponRegistration hands(String hands) {
		this.hands = hands;
		return this;
	}

	/** Solo importa si {@link #hands} es "versatile" (p.ej. "1d10" para una espada larga a dos manos). */
	public WeaponRegistration versatileDice(String versatileDice) {
		this.versatileDice = versatileDice;
		return this;
	}

	/** Subcadenas (minúsculas) que la clase del personaje debe contener para poder usarla; vacío = cualquiera. */
	public WeaponRegistration classes(List<String> classes) {
		this.classes = classes;
		return this;
	}

	/** Para que un resource pack reskinee el arma por número en vez de compartir la textura del ítem base. */
	public WeaponRegistration customModelData(int customModelData) {
		this.customModelData = customModelData;
		return this;
	}

	String id() { return id; }
	String dice() { return dice; }
	String ability() { return ability; }
	String damageType() { return damageType; }
	String hands() { return hands; }
	String versatileDice() { return versatileDice; }
	List<String> classes() { return classes; }
	String displayName() { return displayName; }
	String baseItemId() { return baseItemId; }
	Integer customModelData() { return customModelData; }
}
