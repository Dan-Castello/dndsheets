package net.hawthorn.dndsheets;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <p>Cobertura de 5e: parapetarse detrás de algo sube la CA y las salvaciones de Destreza. Media
 * cobertura da +2, tres cuartos +5, y cobertura total significa que no se te puede apuntar siquiera.</p>
 *
 * <p>Esta es la regla que el mod estaba en mejor posición del mundo para tener y no tenía. Roll20 y Foundry
 * calculan visibilidad con polígonos y capas de niebla <em>para simular</em> un espacio 3D; aquí el espacio
 * 3D es el juego. Un muro de piedra a media altura ya está ahí, con su geometría real, y hasta ahora no
 * significaba nada: disparabas a alguien agachado tras un bloque exactamente igual que a alguien de pie en
 * campo abierto.</p>
 *
 * <p><b>Cómo se mide.</b> Cinco rayos desde el ojo del atacante a cinco puntos del cuerpo del objetivo, y
 * la cobertura sale de cuántos chocan con un bloque. Los puntos van por dentro del volumen y no en sus
 * esquinas, para que el suelo bajo los pies del objetivo no cuente como parapeto. Y los dos laterales se
 * toman <b>perpendiculares a la línea de tiro</b>, no sobre los ejes del mundo: con las esquinas de la caja
 * alineadas a los ejes, disparar en diagonal medía el ancho equivocado y una esquina de pared daba media
 * cobertura o ninguna según hacia dónde mirara el mapa.</p>
 */
public enum Cover {
	NONE(0),
	HALF(2),
	THREE_QUARTERS(5),
	/**
	 * <p>Sin línea de tiro: en 5e no se puede ni elegir como objetivo, y eso lo decide
	 * {@link #blocksTargeting()}.</p>
	 *
	 * <p>Su bonificador es el de tres cuartos y no un infinito porque hay una ruta donde llega igual: una
	 * flecha que YA impactó. Si el proyectil llegó, la cobertura no era total por mucho que digan cinco
	 * rayos, así que se cobra como la mejor cobertura parcial en vez de hacer imposible un golpe que el
	 * mundo acaba de permitir.</p>
	 */
	TOTAL(5);

	/** Cuántos puntos del cuerpo se muestrean. Impar a propósito: no hay empate posible en la mitad. */
	private static final int SAMPLES = 5;
	/** Qué parte del cuerpo se recorre desde el centro. Menos de la mitad, para no rozar suelo ni techo. */
	private static final double BODY_INSET = 0.4;

	private final int bonus;

	Cover(int bonus) {
		this.bonus = bonus;
	}

	/** Lo que suma a la CA del objetivo, y también a sus salvaciones de Destreza (en 5e es el mismo número). */
	public int bonus() {
		return bonus;
	}

	public boolean blocksTargeting() {
		return this == TOTAL;
	}

	public String label() {
		return switch (this) {
			case NONE -> "";
			case HALF -> "media cobertura";
			case THREE_QUARTERS -> "tres cuartos de cobertura";
			case TOTAL -> "cobertura total";
		};
	}

	/**
	 * <p>Grado de cobertura a partir de cuántos puntos del cuerpo quedan tapados. Pura y aparte del mundo
	 * para poder fijarla en el self-test: es la tabla de la regla, y una tabla mal puesta convierte un
	 * parapeto en una pared o al revés.</p>
	 */
	static Cover fromBlocked(int blocked, int total) {
		if (total <= 0 || blocked <= 0) return NONE;
		if (blocked >= total) return TOTAL;
		//"Hasta la mitad tapado" es media cobertura; más que eso, tres cuartos. El SRD lo dice en fracciones
		//del cuerpo, así que se compara en fracciones y no en un número de rayos, y cambiar SAMPLES no
		//reescribe la regla.
		return blocked * 2 <= total ? HALF : THREE_QUARTERS;
	}

	/** Cobertura que le da el terreno al objetivo frente a este atacante. */
	public static Cover between(Entity attacker, Entity target) {
		Level level = attacker.level();
		Vec3 from = attacker.getEyePosition(1.0f);
		AABB box = target.getBoundingBox();
		Vec3 center = box.getCenter();

		Vec3 toTarget = center.subtract(from);
		if (toTarget.lengthSqr() < 1.0E-6) return NONE; //Encima del objetivo: no hay línea que medir.
		Vec3 direction = toTarget.normalize();
		//Perpendicular horizontal a la línea de tiro. Si se dispara en vertical puro no hay lados que medir
		//y el producto vectorial sale nulo: entonces los dos laterales caen sobre el centro, que es
		//exactamente lo correcto (desde arriba, el ancho del objetivo no lo tapa nada).
		Vec3 side = direction.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1.0E-6 ? Vec3.ZERO : side.normalize().scale((box.getXsize() + box.getZsize()) / 2 * BODY_INSET);
		double lift = box.getYsize() * BODY_INSET;

		Vec3[] points = {
			center,
			center.add(0, lift, 0),
			center.add(0, -lift, 0),
			center.add(side),
			center.subtract(side),
		};

		int blocked = 0;
		for (Vec3 point : points) {
			if (isBlocked(level, from, point, attacker)) blocked++;
		}
		return fromBlocked(blocked, SAMPLES);
	}

	/**
	 * <p>¿Hay un bloque sólido entre estos dos puntos? Único sitio del mod que lo pregunta: lo usa la
	 * cobertura y también {@code SpellCastManager} para decidir a quién alcanza un área, que antes tenía su
	 * propia copia del mismo {@code clip}.</p>
	 */
	public static boolean isBlocked(Level level, Vec3 from, Vec3 to, Entity ignore) {
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ignore));
		return hit.getType() != HitResult.Type.MISS;
	}
}
