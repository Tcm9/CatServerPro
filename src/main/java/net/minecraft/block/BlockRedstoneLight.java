package net.minecraft.block;

import java.util.Random;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.bukkit.craftbukkit.event.CraftEventFactory;

public class BlockRedstoneLight extends Block
{
    private final boolean isOn;

    public BlockRedstoneLight(boolean isOn)
    {
        super(Material.REDSTONE_LIGHT);
        this.isOn = isOn;

        if (isOn)
        {
            this.setLightLevel(1.0F);
        }
    }

    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state)
    {
        if (!worldIn.isRemote)
        {
            if (this.isOn && !worldIn.isBlockPowered(pos))
            {
                if (CraftEventFactory.callRedstoneChange(worldIn, pos.getX(), pos.getY(), pos.getZ(), 15, 0).getNewCurrent() != 0) {
                    return;
                }
                worldIn.setBlockState(pos, Blocks.REDSTONE_LAMP.getDefaultState(), 2);
            }
            else if (!this.isOn && worldIn.isBlockPowered(pos))
            {
                if (CraftEventFactory.callRedstoneChange(worldIn, pos.getX(), pos.getY(), pos.getZ(), 0, 15).getNewCurrent() != 15) {
                    return;
                }
                worldIn.setBlockState(pos, Blocks.LIT_REDSTONE_LAMP.getDefaultState(), 2);
            }
        }
    }

    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos)
    {
        if (!worldIn.isRemote)
        {
            if (this.isOn && !worldIn.isBlockPowered(pos))
            {
                worldIn.scheduleUpdate(pos, this, 4);
            }
            else if (!this.isOn && worldIn.isBlockPowered(pos))
            {
                if (CraftEventFactory.callRedstoneChange(worldIn, pos.getX(), pos.getY(), pos.getZ(), 0, 15).getNewCurrent() != 15) {
                    return;
                }
                worldIn.setBlockState(pos, Blocks.LIT_REDSTONE_LAMP.getDefaultState(), 2);
            }
        }
    }

    public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand)
    {
        if (!worldIn.isRemote)
        {
            if (this.isOn && !worldIn.isBlockPowered(pos))
            {
                if (CraftEventFactory.callRedstoneChange(worldIn, pos.getX(), pos.getY(), pos.getZ(), 15, 0).getNewCurrent() != 0) {
                    return;
                }
                worldIn.setBlockState(pos, Blocks.REDSTONE_LAMP.getDefaultState(), 2);
            }
        }
    }

    public Item getItemDropped(IBlockState state, Random rand, int fortune)
    {
        return Item.getItemFromBlock(Blocks.REDSTONE_LAMP);
    }

    public ItemStack getItem(World worldIn, BlockPos pos, IBlockState state)
    {
        return new ItemStack(Blocks.REDSTONE_LAMP);
    }

    protected ItemStack getSilkTouchDrop(IBlockState state)
    {
        return new ItemStack(Blocks.REDSTONE_LAMP);
    }
}