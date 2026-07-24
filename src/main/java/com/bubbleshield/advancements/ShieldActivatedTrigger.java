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
 * the configured diameter, effect id and upgrade-core tier (all
 * {@link MinMaxBounds.Ints} ranges; each is optional and absent means "any", so
 * pre-existing advancement JSONs without a {@code tier} field keep working
 * unchanged).
 */
public class ShieldActivatedTrigger extends SimpleCriterionTrigger<ShieldActivatedTrigger.TriggerInstance> {
	@Override
	public Codec<ShieldActivatedTrigger.TriggerInstance> codec() {
		return ShieldActivatedTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player, int diameter, int effectId, int tier) {
		this.trigger(player, instance -> instance.matches(diameter, effectId, tier));
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player, MinMaxBounds.Ints diameter, MinMaxBounds.Ints effectId, MinMaxBounds.Ints tier)
			implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<ShieldActivatedTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(
					EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(ShieldActivatedTrigger.TriggerInstance::player),
					MinMaxBounds.Ints.CODEC.optionalFieldOf("diameter", MinMaxBounds.Ints.ANY).forGetter(ShieldActivatedTrigger.TriggerInstance::diameter),
					MinMaxBounds.Ints.CODEC.optionalFieldOf("effect_id", MinMaxBounds.Ints.ANY).forGetter(ShieldActivatedTrigger.TriggerInstance::effectId),
					MinMaxBounds.Ints.CODEC.optionalFieldOf("tier", MinMaxBounds.Ints.ANY).forGetter(ShieldActivatedTrigger.TriggerInstance::tier))
				.apply(i, ShieldActivatedTrigger.TriggerInstance::new)
		);

		public boolean matches(int diameter, int effectId, int tier) {
			return this.diameter.matches(diameter) && this.effectId.matches(effectId) && this.tier.matches(tier);
		}
	}
}
