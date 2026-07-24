package com.bubbleshield.advancements;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired for a shield owner when they set a non-empty custom name on their shield.
 */
public class ShieldNamedTrigger extends SimpleCriterionTrigger<ShieldNamedTrigger.TriggerInstance> {
	@Override
	public Codec<ShieldNamedTrigger.TriggerInstance> codec() {
		return ShieldNamedTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player) {
		this.trigger(player, instance -> true);
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<ShieldNamedTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(ShieldNamedTrigger.TriggerInstance::player))
				.apply(i, ShieldNamedTrigger.TriggerInstance::new)
		);
	}
}
