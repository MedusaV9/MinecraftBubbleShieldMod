package com.bubbleshield.advancements;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired for a shield owner when two (or more) of their resonance-linked shields
 * split intercepted projectile damage for the first time.
 */
public class ShieldsLinkedTrigger extends SimpleCriterionTrigger<ShieldsLinkedTrigger.TriggerInstance> {
	@Override
	public Codec<ShieldsLinkedTrigger.TriggerInstance> codec() {
		return ShieldsLinkedTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player) {
		this.trigger(player, instance -> true);
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<ShieldsLinkedTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(ShieldsLinkedTrigger.TriggerInstance::player))
				.apply(i, ShieldsLinkedTrigger.TriggerInstance::new)
		);
	}
}
