package com.bubbleshield.advancements;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired for the (online) owner of a bubble shield when their shield breaks.
 */
public class ShieldBrokenTrigger extends SimpleCriterionTrigger<ShieldBrokenTrigger.TriggerInstance> {
	@Override
	public Codec<ShieldBrokenTrigger.TriggerInstance> codec() {
		return ShieldBrokenTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player) {
		this.trigger(player, instance -> true);
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<ShieldBrokenTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(ShieldBrokenTrigger.TriggerInstance::player))
				.apply(i, ShieldBrokenTrigger.TriggerInstance::new)
		);
	}
}
