package com.bubbleshield.advancements;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.MinMaxBounds;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired when a player successfully activates a bubble shield; conditions can match
 * the configured diameter and effect id (both {@link MinMaxBounds.Ints} ranges).
 */
public class ShieldActivatedTrigger extends SimpleCriterionTrigger<ShieldActivatedTrigger.TriggerInstance> {
	@Override
	public Codec<ShieldActivatedTrigger.TriggerInstance> codec() {
		return ShieldActivatedTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player, int diameter, int effectId) {
		this.trigger(player, instance -> instance.matches(diameter, effectId));
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player, MinMaxBounds.Ints diameter, MinMaxBounds.Ints effectId)
			implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<ShieldActivatedTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(ShieldActivatedTrigger.TriggerInstance::player),
					MinMaxBounds.Ints.CODEC.optionalFieldOf("diameter", MinMaxBounds.Ints.ANY).forGetter(ShieldActivatedTrigger.TriggerInstance::diameter),
					MinMaxBounds.Ints.CODEC.optionalFieldOf("effect_id", MinMaxBounds.Ints.ANY).forGetter(ShieldActivatedTrigger.TriggerInstance::effectId))
				.apply(i, ShieldActivatedTrigger.TriggerInstance::new)
		);

		public boolean matches(int diameter, int effectId) {
			return this.diameter.matches(diameter) && this.effectId.matches(effectId);
		}
	}
}
