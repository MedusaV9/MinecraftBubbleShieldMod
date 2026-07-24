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
 * C7 "unbroken": fired for a shield's (online) owner whenever the shield takes
 * damage, carrying that projector's LIFETIME absorbed total
 * ({@code ShieldState.absorbedTotal}); conditions match the total against an
 * {@code absorbed} {@link MinMaxBounds.Doubles} range (e.g. {@code min: 500}).
 */
public class DamageAbsorbedTrigger extends SimpleCriterionTrigger<DamageAbsorbedTrigger.TriggerInstance> {
	@Override
	public Codec<DamageAbsorbedTrigger.TriggerInstance> codec() {
		return DamageAbsorbedTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player, float absorbedTotal) {
		this.trigger(player, instance -> instance.matches(absorbedTotal));
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player, MinMaxBounds.Doubles absorbed)
			implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<DamageAbsorbedTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(DamageAbsorbedTrigger.TriggerInstance::player),
					MinMaxBounds.Doubles.CODEC.optionalFieldOf("absorbed", MinMaxBounds.Doubles.ANY).forGetter(DamageAbsorbedTrigger.TriggerInstance::absorbed))
				.apply(i, DamageAbsorbedTrigger.TriggerInstance::new)
		);

		public boolean matches(float absorbedTotal) {
			return this.absorbed.matches(absorbedTotal);
		}
	}
}
