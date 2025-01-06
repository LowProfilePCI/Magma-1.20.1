package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;

public class CraftEvokerFangs extends CraftEntity implements EvokerFangs {

    public CraftEvokerFangs(CraftServer server, net.minecraft.world.entity.projectile.EvokerFangs entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.projectile.EvokerFangs getHandle() {
        return (net.minecraft.world.entity.projectile.EvokerFangs) super.getHandle();
    }

    @Override
    public String toString() {
        return "CraftEvokerFangs";
    }

    @Override
    public LivingEntity getOwner() {
        net.minecraft.world.entity.LivingEntity owner = getHandle().getOwner();

        return (owner == null) ? null : (LivingEntity) owner.getBukkitEntity();
    }

    @Override
    public void setOwner(LivingEntity owner) {
        getHandle().setOwner(owner == null ? null : ((CraftLivingEntity) owner).getHandle());
    }
}
