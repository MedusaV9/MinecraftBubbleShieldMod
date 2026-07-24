package com.bubbleshield.advancements;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired for a shield owner when they apply a dye color override to their shield
 * (a reset to the effect's authored palette does not count).
 */
public class ShieldRecoloredTrigger extends SimpleCriterionTrigger<ShieldRecoloredTrigger.TriggerInstance> {
	@Override
	public Codec<ShieldRecoloredTrigger.TriggerInstance> codec() {
		return ShieldRecoloredTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player) {
		this.trigger(player, instance -> true);
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<ShieldRecoloredTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(ShieldRecoloredTrigger.TriggerInstance::player))
				.apply(i, ShieldRecoloredTrigger.TriggerInstance::new)
		);
	}
}
