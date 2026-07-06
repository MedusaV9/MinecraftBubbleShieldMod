package com.bubbleshield.advancements;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.predicates.ContextAwarePredicate;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fired for a shield owner when they add a player name to their shield's whitelist.
 */
public class PlayerWhitelistedTrigger extends SimpleCriterionTrigger<PlayerWhitelistedTrigger.TriggerInstance> {
	@Override
	public Codec<PlayerWhitelistedTrigger.TriggerInstance> codec() {
		return PlayerWhitelistedTrigger.TriggerInstance.CODEC;
	}

	public void trigger(ServerPlayer player) {
		this.trigger(player, instance -> true);
	}

	public record TriggerInstance(Optional<ContextAwarePredicate> player) implements SimpleCriterionTrigger.SimpleInstance {
		public static final Codec<PlayerWhitelistedTrigger.TriggerInstance> CODEC = RecordCodecBuilder.create(
			i -> i.group(EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(PlayerWhitelistedTrigger.TriggerInstance::player))
				.apply(i, PlayerWhitelistedTrigger.TriggerInstance::new)
		);
	}
}
