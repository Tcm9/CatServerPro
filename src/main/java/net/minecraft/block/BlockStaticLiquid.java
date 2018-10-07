package net.minecraft.block;

import java.util.Random;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.bukkit.craftbukkit.event.CraftEventFactory;

public class BlockStaticLiquid extends BlockLiquid
{
    protected BlockStaticLiquid(Material materialIn)
    {
        super(materialIn);
        this.setTickRandomly(false);

        if (materialIn == Material.LAVA)
        {
            this.setTickRandomly(true);
        }
    }

    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos)
    {
        if (!this.checkForMixing(worldIn, pos, state))
        {
            this.updateLiquid(worldIn, pos, state);
        }
    }

    private void updateLiquid(World worldIn, BlockPos pos, IBlockState state)
    {
        BlockDynamicLiquid blockdynamicliquid = getFlowingBlock(this.blockMaterial);
        worldIn.setBlockState(pos, blockdynamicliquid.getDefaultState().withProperty(LEVEL, state.getValue(LEVEL)), 2);
        worldIn.scheduleUpdate(pos, blockdynamicliquid, this.tickRate(worldIn));
    }

    public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)
    {
        if (this.blockMaterial == Material.LAVA)
        {
            if (worldIn.getGameRules().getBoolean("doFireTick"))
            {
                int i = rand.nextInt(3);

                if (i > 0)
                {
                    BlockPos blockpos = pos;

                    for (int j = 0; j < i; ++j)
                    {
                        blockpos = blockpos.add(rand.nextInt(3) - 1, 1, rand.nextInt(3) - 1);

                        if (blockpos.getY() >= 0 && blockpos.getY() < worldIn.getHeight() && !worldIn.isBlockLoaded(blockpos))
                        {
                            return;
                        }

                        IBlockState block = worldIn.getBlockState(blockpos);

                        if (block.getBlock().isAir(block, worldIn, blockpos))
                        {
                            if (this.isSurroundingBlockFlammable(worldIn, blockpos))
                            {
                                if (worldIn.getBlockState(blockpos) != Blocks.FIRE) {
                                    if (CraftEventFactory.callBlockIgniteEvent(worldIn, blockpos.getX(), blockpos.getY(), blockpos.getZ(), pos.getX(), pos.getY(), pos.getZ()).isCancelled()) {
                                        continue;
                                    }
                                    net.minecraftforge.event.ForgeEventFactory.fireFluidPlaceBlockEvent(worldIn, blockpos, pos, Blocks.FIRE.getDefaultState());
                                }
                                worldIn.setBlockState(blockpos, Blocks.FIRE.getDefaultState());
                                return;
                            }
                        }
                        else if (block.getMaterial().blocksMovement())
                        {
                            return;
                        }
                    }
                }
                else
                {
                    for (int k = 0; k < 3; ++k)
                    {
                        BlockPos blockpos1 = pos.add(rand.nextInt(3) - 1, 0, rand.nextInt(3) - 1);

                        if (blockpos1.getY() >= 0 && blockpos1.getY() < 256 && !worldIn.isBlockLoaded(blockpos1))
                        {
                            return;
                        }

                        if (worldIn.isAirBlock(blockpos1.up()) && this.getCanBlockBurn(worldIn, blockpos1))
                        {
                            // CraftBukkit start - Prevent lava putting something on fire
                            BlockPos up = blockpos1.up();
                            if (worldIn.getBlockState(up) != Blocks.FIRE) {
                                if (CraftEventFactory.callBlockIgniteEvent(worldIn, up.getX(), up.getY(), up.getZ(), pos.getX(), pos.getY(), pos.getZ()).isCancelled()) {
                                    net.minecraftforge.event.ForgeEventFactory.fireFluidPlaceBlockEvent(worldIn, blockpos1.up(), pos, Blocks.FIRE.getDefaultState());
                                    continue;
                                }
                            }
                            // CraftBukkit end
                            worldIn.setBlockState(blockpos1.up(), Blocks.FIRE.getDefaultState());
                        }
                    }
                }
            }
        }
    }

    protected boolean isSurroundingBlockFlammable(World worldIn, BlockPos pos)
    {
        for (EnumFacing enumfacing : EnumFacing.values())
        {
            if (this.getCanBlockBurn(worldIn, pos.offset(enumfacing)))
            {
                return true;
            }
        }

        return false;
    }

    private boolean getCanBlockBurn(World worldIn, BlockPos pos)
    {
        return pos.getY() >= 0 && pos.getY() < 256 && !worldIn.isBlockLoaded(pos) ? false : worldIn.getBlockState(pos).getMaterial().getCanBurn();
    }
}